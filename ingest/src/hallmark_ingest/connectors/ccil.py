from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from hallmark_ingest.connectors.base import Outcome
from hallmark_ingest.lake import BronzeKey, Lake
from hallmark_ingest.net import PoliteClient

SOURCE = "ccil"
IST = ZoneInfo("Asia/Kolkata")

SNAPSHOTS: dict[str, tuple[str, str]] = {
    "ndsom_watch": (
        "https://www.ccilindia.com/rbi-nds-om1",
        "https://www.ccilindia.com/rbi-nds-om1?p_p_id=com_ccil_ndsom_entire_CCILNdsOM_EntirePortlet_INSTANCE_zavb"
        "&p_p_lifecycle=2&p_p_state=normal&p_p_mode=view&p_p_resource_id=ndsom&p_p_cacheability=cacheLevelPage",
    ),
    "far_holdings": (
        "https://www.ccilindia.com/fpi-home-page",
        "https://www.ccilindia.com/fpi-home-page?p_p_id=FPI_FPIPortlet_INSTANCE_rkvf&p_p_lifecycle=2"
        "&p_p_state=normal&p_p_mode=view&p_p_resource_id=FPI&p_p_cacheability=cacheLevelPage",
    ),
}


def fetch_snapshot(client: PoliteClient, lake: Lake, dataset: str, now: datetime | None = None) -> Outcome:
    page, resource = SNAPSHOTS[dataset]
    stamp = (now or datetime.now(IST)).astimezone(IST)
    client.get(page)
    result = client.post(resource, data="", headers={"X-Requested-With": "XMLHttpRequest"})
    key = BronzeKey.for_date(SOURCE, dataset, stamp.date(), f"{dataset}_{stamp:%H%M}.json")
    if result.ok and result.content.lstrip().startswith(b"{"):
        lake.write_bronze(key, result)
        return Outcome.DOWNLOADED
    lake.record_missing(key, result)
    return Outcome.MISSING
