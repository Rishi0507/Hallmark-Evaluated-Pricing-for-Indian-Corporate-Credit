from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from enum import StrEnum

import structlog

from hallmark_ingest.lake import BronzeKey, Lake
from hallmark_ingest.net import FetchResult, PoliteClient

log = structlog.get_logger(__name__)


class Outcome(StrEnum):
    DOWNLOADED = "downloaded"
    CACHED = "cached"
    MISSING = "missing"
    REJECTED = "rejected"


@dataclass(frozen=True)
class DailyFile:
    source: str
    name: str
    url_for: Callable[[date], str]
    filename_for: Callable[[date], str]
    required_marker: bytes
    headers: dict[str, str] | None = None
    missing_status: frozenset[int] = frozenset({404})

    def key(self, day: date) -> BronzeKey:
        return BronzeKey.for_date(self.source, self.name, day, self.filename_for(day))

    def accept(self, result: FetchResult) -> bool:
        if not result.ok or result.looks_like_html():
            return False
        return self.required_marker in result.content[:4096]


def fetch_daily(
    client: PoliteClient,
    lake: Lake,
    spec: DailyFile,
    day: date,
    *,
    force: bool = False,
    retry_missing: bool = False,
) -> Outcome:
    key = spec.key(day)
    if not force and lake.has(key):
        return Outcome.CACHED
    if not force and not retry_missing and lake.known_missing(key):
        return Outcome.MISSING
    result = client.get(spec.url_for(day), headers=spec.headers)
    if spec.accept(result):
        lake.write_bronze(key, result)
        return Outcome.DOWNLOADED
    if result.status in spec.missing_status or result.looks_like_html():
        lake.record_missing(key, result)
        return Outcome.MISSING
    log.warning(
        "connector.rejected",
        source=spec.source,
        dataset=spec.name,
        day=str(day),
        status=result.status,
        content_type=result.content_type,
    )
    lake.record_missing(key, result)
    return Outcome.REJECTED
