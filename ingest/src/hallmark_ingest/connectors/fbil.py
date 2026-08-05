from __future__ import annotations

from datetime import date

from hallmark_ingest.connectors.base import DailyFile, Outcome
from hallmark_ingest.lake import BronzeKey, Lake
from hallmark_ingest.net import PoliteClient

BASE = "https://www.fbil.org.in/wasdm"
SOURCE = "fbil"
HEADERS = {"Referer": "https://www.fbil.org.in/", "Accept": "application/json, */*"}

GSEC_VALUATION = DailyFile(
    source=SOURCE,
    name="gsec_valuation",
    url_for=lambda d: f"{BASE}/gsec/downloadPublished?date={d.isoformat()}",
    filename_for=lambda d: f"fbil_gsec_{d:%Y%m%d}.xlsx",
    required_marker=b"PK",
    headers=HEADERS,
    missing_status=frozenset({404, 500}),
)

RANGE_DATASETS = {
    "tbill_curve": "tbill/fetchfiltered",
    "fx_reference": "refrates/fetchfiltered",
}


def fetch_range(
    client: PoliteClient,
    lake: Lake,
    dataset: str,
    start: date,
    end: date,
    *,
    force: bool = False,
) -> Outcome:
    key = BronzeKey(SOURCE, dataset, f"year={start.year}", f"{dataset}_{start:%Y%m%d}_{end:%Y%m%d}.json")
    if not force and lake.has(key):
        return Outcome.CACHED
    result = client.get(
        f"{BASE}/{RANGE_DATASETS[dataset]}",
        params={"fromDate": start.isoformat(), "toDate": end.isoformat(), "authenticated": "false"},
        headers=HEADERS,
    )
    if result.ok and result.content.lstrip().startswith(b"["):
        lake.write_bronze(key, result)
        return Outcome.DOWNLOADED
    lake.record_missing(key, result)
    return Outcome.MISSING
