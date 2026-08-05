from __future__ import annotations

from datetime import date

from hallmark_ingest.connectors.base import Outcome
from hallmark_ingest.lake import BronzeKey, Lake
from hallmark_ingest.net import PoliteClient

BASE = "https://indiabondinfo.nsdl.com/bds-service/v1/public"
SOURCE = "nsdl"
HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Referer": "https://indiabondinfo.nsdl.com/CBDServices/",
}

ENDPOINTS: dict[str, str] = {
    "instrument": "isins",
    "ratings": "bdsinfo/credit-ratings",
    "cashflows": "bdsinfo/coupondetail",
    "redemption": "bdsinfo/redemptions",
}


def key_for(endpoint: str, isin: str, snapshot: date) -> BronzeKey:
    return BronzeKey.for_snapshot(SOURCE, endpoint, snapshot, f"{isin}.json")


def latest_snapshot(lake: Lake, endpoint: str, isin: str) -> date | None:
    base = lake.bronze_root / SOURCE / endpoint
    if not base.exists():
        return None
    snaps = sorted(
        (p.name.split("=", 1)[1] for p in base.iterdir() if (p / f"{isin}.json").is_file()),
        reverse=True,
    )
    return date.fromisoformat(snaps[0]) if snaps else None


def fetch_isin(
    client: PoliteClient,
    lake: Lake,
    endpoint: str,
    isin: str,
    snapshot: date,
    *,
    max_age_days: int | None = None,
) -> Outcome:
    previous = latest_snapshot(lake, endpoint, isin)
    if previous is not None and (max_age_days is None or (snapshot - previous).days <= max_age_days):
        return Outcome.CACHED
    key = key_for(endpoint, isin, snapshot)
    if lake.known_missing(key):
        return Outcome.MISSING
    result = client.get(f"{BASE}/{ENDPOINTS[endpoint]}", params={"isin": isin}, headers=HEADERS)
    body = result.content.lstrip()
    if result.ok and body.startswith((b"{", b"[")) and body not in (b"[]", b"{}"):
        lake.write_bronze(key, result)
        return Outcome.DOWNLOADED
    lake.record_missing(key, result)
    return Outcome.MISSING
