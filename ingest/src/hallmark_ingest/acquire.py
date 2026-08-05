from __future__ import annotations

import threading
from collections import Counter
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor
from datetime import date

import httpx
import structlog

from hallmark_ingest.config import Settings
from hallmark_ingest.connectors import ccil, fbil, nsdl, nse
from hallmark_ingest.connectors.base import Outcome, fetch_daily
from hallmark_ingest.dates import month_end_weekdays, weekdays, year_slices
from hallmark_ingest.lake import Lake
from hallmark_ingest.net import PoliteClient, TransientHttpError

log = structlog.get_logger(__name__)


def make_client(settings: Settings) -> PoliteClient:
    return PoliteClient(
        user_agent=settings.user_agent,
        intervals={
            "nsearchives.nseindia.com": settings.nse_min_interval_s,
            "www.nseindia.com": settings.nse_min_interval_s,
            "www.fbil.org.in": settings.fbil_min_interval_s,
            "indiabondinfo.nsdl.com": settings.nsdl_min_interval_s,
            "www.ccilindia.com": settings.ccil_min_interval_s,
        },
        timeout_s=settings.http_timeout_s,
    )


def acquire_nse(
    client: PoliteClient, lake: Lake, start: date, end: date, *, recheck_days: int = 5
) -> Counter[str]:
    stats: Counter[str] = Counter()
    recheck_from = date.fromordinal(end.toordinal() - recheck_days)
    for day in weekdays(start, end):
        for spec in nse.DAILY_FILES:
            outcome = fetch_daily(client, lake, spec, day, retry_missing=day >= recheck_from)
            stats[f"{spec.name}:{outcome}"] += 1
        if day.day == 1 or day == start:
            log.info("acquire.nse.progress", day=str(day), stats=dict(stats))
    for day in month_end_weekdays(start, end):
        outcome = fetch_daily(client, lake, nse.NTRP_MASTER, day)
        if outcome == Outcome.MISSING:
            for back in range(1, 6):
                prev = date.fromordinal(day.toordinal() - back)
                if prev.weekday() < 5 and fetch_daily(client, lake, nse.NTRP_MASTER, prev) != Outcome.MISSING:
                    break
        stats[f"ntrp_master:{outcome}"] += 1
    return stats


def acquire_fbil(client: PoliteClient, lake: Lake, start: date, end: date) -> Counter[str]:
    stats: Counter[str] = Counter()
    recheck_from = date.fromordinal(end.toordinal() - 5)
    for day in weekdays(start, end):
        outcome = fetch_daily(client, lake, fbil.GSEC_VALUATION, day, retry_missing=day >= recheck_from)
        stats[f"gsec_valuation:{outcome}"] += 1
    for lo, hi in year_slices(start, end):
        current_year = hi.year == end.year
        for dataset in fbil.RANGE_DATASETS:
            stats[f"{dataset}:{fbil.fetch_range(client, lake, dataset, lo, hi, force=current_year)}"] += 1
    return stats


def acquire_nsdl(
    client: PoliteClient,
    lake: Lake,
    isins: Iterable[str],
    snapshot: date,
    *,
    endpoints: Iterable[str] = tuple(nsdl.ENDPOINTS),
    rating_max_age_days: int = 7,
    workers: int = 3,
) -> Counter[str]:
    stats: Counter[str] = Counter()
    eps = tuple(endpoints)
    lock = threading.Lock()

    def enrich(isin: str) -> Counter[str]:
        local: Counter[str] = Counter()
        for endpoint in eps:
            if endpoint == "redemption" and not _needs_redemption(lake, isin, snapshot):
                continue
            max_age = rating_max_age_days if endpoint == "ratings" else None
            try:
                outcome = nsdl.fetch_isin(client, lake, endpoint, isin, snapshot, max_age_days=max_age)
            except (TransientHttpError, httpx.TransportError) as exc:
                log.warning("acquire.nsdl.failed", isin=isin, endpoint=endpoint, error=str(exc))
                local[f"{endpoint}:error"] += 1
                continue
            local[f"{endpoint}:{outcome}"] += 1
        return local

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for i, result in enumerate(pool.map(enrich, isins), start=1):
            with lock:
                stats.update(result)
            if i % 250 == 0:
                log.info("acquire.nsdl.progress", isins=i, stats=dict(stats))
    return stats


def _needs_redemption(lake: Lake, isin: str, today: date) -> bool:
    from hallmark_ingest.parsers.nsdl import parse_cashflows

    snap = nsdl.latest_snapshot(lake, "cashflows", isin)
    if snap is None:
        return False
    _, flows = parse_cashflows(lake.bronze_path(nsdl.key_for("cashflows", isin, snap)), snap)
    return not any(f["is_principal"] and f["payment_date"] > today for f in flows)


def acquire_ccil(client: PoliteClient, lake: Lake) -> Counter[str]:
    stats: Counter[str] = Counter()
    for dataset in ccil.SNAPSHOTS:
        stats[f"{dataset}:{ccil.fetch_snapshot(client, lake, dataset)}"] += 1
    return stats


def acquire_market(settings: Settings, lake: Lake, start: date, end: date) -> dict[str, dict[str, int]]:
    with make_client(settings) as client, ThreadPoolExecutor(max_workers=2) as pool:
        nse_future = pool.submit(acquire_nse, client, lake, start, end)
        fbil_future = pool.submit(acquire_fbil, client, lake, start, end)
        return {"nse": dict(nse_future.result()), "fbil": dict(fbil_future.result())}
