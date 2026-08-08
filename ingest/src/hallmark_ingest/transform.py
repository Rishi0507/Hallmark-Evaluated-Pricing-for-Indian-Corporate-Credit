from __future__ import annotations

from collections import Counter, defaultdict
from datetime import date, datetime
from pathlib import Path
from typing import Any

import polars as pl
import structlog

from hallmark_ingest.connectors import nse as nse_conn
from hallmark_ingest.lake import Lake
from hallmark_ingest.parsers import ccil, fbil, nsdl
from hallmark_ingest.parsers import nse as nse_parse
from hallmark_ingest.quality.checks import apply_suite

log = structlog.get_logger(__name__)


def _stale(target: Path, inputs: list[Path]) -> bool:
    if not target.exists():
        return True
    built = target.stat().st_mtime
    return any(p.stat().st_mtime > built for p in inputs)


def _write(lake: Lake, dataset: str, partition: str, frame: pl.DataFrame) -> int:
    clean = apply_suite(dataset, partition, frame, lake.root / "silver" / "_dq")
    lake.write_silver(dataset, partition, clean)
    return clean.height


def _group_by_partition(lake: Lake, source: str, datasets: list[str]) -> dict[str, dict[str, Path]]:
    grouped: dict[str, dict[str, Path]] = defaultdict(dict)
    for dataset in datasets:
        for part, path in lake.iter_bronze(source, dataset):
            grouped[part][dataset] = path
    return grouped


def transform_nse(lake: Lake, *, rebuild: bool = False) -> Counter[str]:
    stats: Counter[str] = Counter()

    for part, path in lake.iter_bronze("nse", "cbm_bhavcopy"):
        target = lake.silver_path("trades_reported", f"dt={part}")
        if rebuild or _stale(target, [path]):
            frame = nse_parse.parse_bhavcopy(path, date.fromisoformat(part))
            stats["trades_reported"] += _write(lake, "trades_reported", f"dt={part}", frame)

    settle_sets = [s.name for s in nse_conn.SETTLEMENT_FILES] + ["cbm_fail", "cp_settle", "cd_settle"]
    for part, files in sorted(_group_by_partition(lake, "nse", settle_sets).items()):
        target = lake.silver_path("settlements", f"dt={part}")
        if not (rebuild or _stale(target, list(files.values()))):
            continue
        day = date.fromisoformat(part)
        frames = []
        for dataset, path in files.items():
            if dataset.startswith("cbm_settle_"):
                listing, counterparty = nse_conn.SETTLEMENT_CLASSES[dataset.removeprefix("cbm_settle_")]
                frames.append(
                    nse_parse.parse_settlement(
                        path, day, listing=listing, counterparty_class=counterparty, status="SETTLED"
                    )
                )
            elif dataset == "cbm_fail":
                frames.append(
                    nse_parse.parse_settlement(
                        path, day, listing=None, counterparty_class=None, status="FAILED"
                    )
                )
            else:
                kind = "CP" if dataset == "cp_settle" else "CD"
                frames.append(
                    nse_parse.parse_settlement(
                        path,
                        day,
                        listing="LISTED",
                        counterparty_class=None,
                        status="SETTLED",
                        instrument=kind,
                    )
                )
        frame = pl.concat(frames, how="vertical_relaxed") if frames else pl.DataFrame()
        stats["settlements"] += _write(lake, "settlements", f"dt={part}", frame)

    for part, path in lake.iter_bronze("nse", "ntrp_master"):
        target = lake.silver_path("ntrp_master", f"dt={part}")
        if rebuild or _stale(target, [path]):
            frame = nse_parse.parse_ntrp_master(path, date.fromisoformat(part))
            stats["ntrp_master"] += _write(lake, "ntrp_master", f"dt={part}", frame)
    return stats


def transform_fbil(lake: Lake, *, rebuild: bool = False) -> Counter[str]:
    stats: Counter[str] = Counter()
    for part, path in lake.iter_bronze("fbil", "gsec_valuation"):
        day = date.fromisoformat(part)
        for dataset, parser in (("gsec_prices", fbil.parse_gsec_prices), ("par_curve", fbil.parse_par_curve)):
            target = lake.silver_path(dataset, f"dt={part}")
            if rebuild or _stale(target, [path]):
                stats[dataset] += _write(lake, dataset, f"dt={part}", parser(path, day))

    for dataset, parser in (
        ("tbill_curve", fbil.parse_tbill_curve),
        ("fx_reference", fbil.parse_fx_reference),
    ):
        paths = [p for _, p in lake.iter_bronze("fbil", dataset)]
        outputs = [lake.silver_path(dataset, part) for part in lake.silver_partitions(dataset)]
        if not paths or (outputs and not rebuild and not any(_stale(o, paths) for o in outputs)):
            continue
        frame = parser(paths)
        for (year,), part_frame in frame.group_by(pl.col("as_of").dt.year(), maintain_order=True):
            stats[dataset] += _write(lake, dataset, f"year={year}", part_frame)
    return stats


def transform_nsdl(lake: Lake, *, rebuild: bool = False) -> Counter[str]:
    stats: Counter[str] = Counter()
    snapshots: set[str] = set()
    for endpoint in ("instrument", "ratings", "cashflows", "redemption"):
        base = lake.bronze_root / "nsdl" / endpoint
        if base.exists():
            snapshots |= {p.name.split("=", 1)[1] for p in base.iterdir() if p.is_dir()}

    for snap in sorted(snapshots):
        snapshot = date.fromisoformat(snap)
        part = f"snap={snap}"

        def files(endpoint: str, snap: str = snap) -> list[Path]:
            d = lake.bronze_root / "nsdl" / endpoint / f"snap={snap}"
            return sorted(d.glob("*.json")) if d.exists() else []

        instrument_files = files("instrument")
        if instrument_files and (
            rebuild or _stale(lake.silver_path("nsdl_instrument", part), instrument_files)
        ):
            rows = [nsdl.parse_instrument(p, snapshot) for p in instrument_files]
            stats["nsdl_instrument"] += _write(
                lake, "nsdl_instrument", part, pl.DataFrame(rows, schema=nsdl.INSTRUMENT_SCHEMA)
            )

        rating_files = files("ratings")
        if rating_files and (rebuild or _stale(lake.silver_path("nsdl_ratings", part), rating_files)):
            rating_rows: list[dict[str, Any]] = []
            for p in rating_files:
                rating_rows.extend(nsdl.parse_ratings(p, snapshot))
            stats["nsdl_ratings"] += _write(
                lake, "nsdl_ratings", part, pl.DataFrame(rating_rows, schema=nsdl.RATING_SCHEMA)
            )

        cash_files = files("cashflows")
        if cash_files and (rebuild or _stale(lake.silver_path("nsdl_cashflows", part), cash_files)):
            terms_rows: list[dict[str, Any]] = []
            flow_rows: list[dict[str, Any]] = []
            for p in cash_files:
                terms, flows = nsdl.parse_cashflows(p, snapshot)
                if terms:
                    terms_rows.append(terms)
                flow_rows.extend(flows)
            stats["nsdl_terms"] += _write(
                lake, "nsdl_terms", part, pl.DataFrame(terms_rows, schema=nsdl.TERMS_SCHEMA)
            )
            stats["nsdl_cashflows"] += _write(
                lake, "nsdl_cashflows", part, pl.DataFrame(flow_rows, schema=nsdl.CASHFLOW_SCHEMA)
            )

        red_files = files("redemption")
        if red_files and (rebuild or _stale(lake.silver_path("nsdl_redemption", part), red_files)):
            red_rows = [nsdl.parse_redemption(p, snapshot, p.stem) for p in red_files]
            stats["nsdl_redemption"] += _write(
                lake, "nsdl_redemption", part, pl.DataFrame(red_rows, schema=nsdl.REDEMPTION_SCHEMA)
            )
    return stats


def transform_ccil(lake: Lake, *, rebuild: bool = False) -> Counter[str]:
    stats: Counter[str] = Counter()
    by_day: dict[str, list[Path]] = defaultdict(list)
    for part, path in lake.iter_bronze("ccil", "ndsom_watch"):
        by_day[part].append(path)
    for part, paths in by_day.items():
        latest = max(paths, key=lambda p: p.name)
        target = lake.silver_path("ndsom_watch", f"dt={part}")
        if rebuild or _stale(target, [latest]):
            hhmm = latest.stem.rsplit("_", 1)[-1]
            captured = datetime.strptime(f"{part} {hhmm}", "%Y-%m-%d %H%M")
            stats["ndsom_watch"] += _write(
                lake,
                "ndsom_watch",
                f"dt={part}",
                ccil.parse_ndsom(latest, date.fromisoformat(part), captured),
            )
    for part, path in lake.iter_bronze("ccil", "far_holdings"):
        target = lake.silver_path("far_limits", f"dt={part}")
        if rebuild or _stale(target, [path]):
            stats["far_limits"] += _write(
                lake, "far_limits", f"dt={part}", ccil.parse_far_limits(path, date.fromisoformat(part))
            )
    return stats


def transform_all(lake: Lake, *, rebuild: bool = False) -> dict[str, int]:
    stats: Counter[str] = Counter()
    for step in (transform_nse, transform_fbil, transform_nsdl, transform_ccil):
        result = step(lake, rebuild=rebuild)
        log.info("transform.step", step=step.__name__, rows=dict(result))
        stats.update(result)
    return dict(stats)
