from __future__ import annotations

import json
import shutil
from datetime import date
from pathlib import Path

import httpx
import polars as pl
import pytest

from hallmark_ingest import events as ev
from hallmark_ingest.acquire import acquire_fbil, acquire_nsdl, acquire_nse
from hallmark_ingest.connectors import nse
from hallmark_ingest.connectors.base import Outcome, fetch_daily
from hallmark_ingest.dates import month_end_weekdays, parse_flexible, weekdays, year_slices
from hallmark_ingest.lake import BronzeKey, Lake
from hallmark_ingest.net import PoliteClient
from hallmark_ingest.publish import FileSink, SchemaValidator, publish_days, publish_refdata
from hallmark_ingest.quality.checks import DataQualityError, apply_suite
from hallmark_ingest.transform import transform_all
from hallmark_ingest.universe import active_isins

DAY = date(2026, 10, 14)


def fake_web(fixtures: Path) -> httpx.MockTransport:
    files = {
        "cbm_trd20261014.csv": "nse/cbm_trd20261014.csv",
        "cbm_list_man_14102026.csv": "nse/cbm_list_man_14102026.csv",
        "cbm_list_non_man_14102026.csv": "nse/cbm_list_non_man_14102026.csv",
        "cbm_fail_14102026.csv": "nse/cbm_fail_14102026.csv",
        "CD_SettlementOrderReport_14102026.csv": "nse/CD_SettlementOrderReport_14102026.csv",
        "Corporate_bond_report_14-Oct-2026.csv": "nse/Corporate_bond_report_14-Oct-2026.csv",
    }

    def handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        name = url.rsplit("/", 1)[-1]
        if name in files:
            return httpx.Response(200, content=(fixtures / files[name]).read_bytes())
        if "gsec/downloadPublished" in url:
            if request.url.params.get("date") == DAY.isoformat():
                return httpx.Response(
                    200, content=(fixtures / "fbil" / "fbil_gsec_20261008.xlsx").read_bytes()
                )
            return httpx.Response(500, json={"status": 500})
        if "tbill/fetchfiltered" in url:
            return httpx.Response(200, content=(fixtures / "fbil" / "tbill_2025.json").read_bytes())
        if "refrates/fetchfiltered" in url:
            return httpx.Response(200, content=(fixtures / "fbil" / "fx_2025.json").read_bytes())
        if "indiabondinfo" in url:
            isin = request.url.params.get("isin")
            endpoint = {
                "isins": "instrument",
                "credit-ratings": "ratings",
                "coupondetail": "cashflows",
                "redemptions": "redemption",
            }[url.split("?", maxsplit=1)[0].rsplit("/", 1)[-1]]
            path = fixtures / "nsdl" / f"{isin}_{endpoint}.json"
            if path.exists():
                return httpx.Response(200, content=path.read_bytes())
            return httpx.Response(400, json={"message": "No Record Found"})
        return httpx.Response(404, text="<!DOCTYPE html><html>not found</html>")

    return httpx.MockTransport(handler)


@pytest.fixture
def client(fixtures: Path) -> PoliteClient:
    return PoliteClient("test", intervals={}, default_interval_s=0, transport=fake_web(fixtures))


def test_calendar_helpers() -> None:
    assert len(list(weekdays(date(2026, 10, 10), date(2026, 10, 16)))) == 5
    assert month_end_weekdays(date(2026, 9, 1), date(2026, 10, 14))[-2:] == [
        date(2026, 9, 30),
        date(2026, 10, 14),
    ]
    assert list(year_slices(date(2025, 6, 1), date(2026, 2, 1)))[1] == (date(2026, 1, 1), date(2026, 2, 1))
    assert parse_flexible("13-Oct-26") == date(2026, 10, 13)
    with pytest.raises(ValueError):
        parse_flexible("yesterday")


def test_daily_fetch_is_idempotent_and_records_misses(tmp_path: Path, client: PoliteClient) -> None:
    lake = Lake(tmp_path)
    assert fetch_daily(client, lake, nse.BHAVCOPY, DAY) == Outcome.DOWNLOADED
    assert fetch_daily(client, lake, nse.BHAVCOPY, DAY) == Outcome.CACHED
    assert fetch_daily(client, lake, nse.BHAVCOPY, date(2026, 10, 15)) == Outcome.MISSING
    assert fetch_daily(client, lake, nse.BHAVCOPY, date(2026, 10, 15)) == Outcome.MISSING
    manifest = lake.manifest("nse")
    assert [m.status for m in manifest] == [200, 404]
    assert manifest[0].sha256 and manifest[0].bytes > 0


def test_end_to_end_bronze_silver_events(tmp_path: Path, client: PoliteClient, fixtures: Path) -> None:
    lake = Lake(tmp_path)
    nse_stats = acquire_nse(client, lake, DAY, DAY)
    assert nse_stats["cbm_bhavcopy:downloaded"] == 1 and nse_stats["cbm_settle_unlist_man:missing"] == 1
    fbil_stats = acquire_fbil(client, lake, DAY, DAY)
    assert fbil_stats["gsec_valuation:downloaded"] == 1
    isins = ["INE053F08403", "INE296A07UB9", "INE476A08282", "INE000000000"]
    nsdl_stats = acquire_nsdl(client, lake, isins, DAY, workers=2)
    assert nsdl_stats["instrument:downloaded"] == 3 and nsdl_stats["instrument:missing"] == 1
    assert nsdl_stats.get("redemption:downloaded", 0) == 1

    stats = transform_all(lake)
    assert stats["trades_reported"] == 40 and stats["settlements"] > 60 and stats["par_curve"] == 200
    assert stats["nsdl_instrument"] == 3
    assert transform_all(lake) == {}
    assert active_isins(lake, limit=5)

    validator = SchemaValidator()
    sink = FileSink(tmp_path / "events")
    refdata = publish_refdata(lake, sink, validate=True)
    days = publish_days(lake, sink, DAY, DAY, validate=True)
    sink.close()
    assert refdata["SecurityMasterRecord"] > 3
    assert days["ReportedTrade"] == 40 and days["EndOfDay"] == 1 and days["MarketCurve"] == 1
    lines = (tmp_path / "events" / "control.eod.v1.jsonl").read_text().splitlines()
    eod = json.loads(lines[0])
    validator.validate(eod)
    assert eod["payload"]["counts"]["reported"] == 40


def test_event_ids_are_deterministic() -> None:
    a = ev.make_event("ReportedTrade", "X", DAY, {"b": 1.0, "a": date(2026, 1, 1)})
    b = ev.make_event("ReportedTrade", "X", DAY, {"a": date(2026, 1, 1), "b": 1.0})
    c = ev.make_event("ReportedTrade", "X", DAY, {"a": date(2026, 1, 1), "b": float("nan")})
    assert a["eventId"] == b["eventId"] != c["eventId"]
    assert c["payload"]["b"] is None


def test_quality_suite_quarantines_and_rejects(tmp_path: Path) -> None:
    good = pl.DataFrame(
        {
            "isin": ["INE423A07484", "INE296A07UB9"],
            "tenor_years": [1.0, 2.0],
            "par_yield_semiannual": [6.0, 6.5],
        }
    )
    bad = pl.DataFrame(
        {
            "isin": ["INE423A07484", "INE296A07UB9", "INE053F08403", "BAD"],
            "trade_date": [DAY] * 4,
            "wap": [100.0] * 4,
            "way": [8.0, 8.0, 99.0, 8.0],
            "total_trade_value_lakh": [1.0] * 4,
        }
    )
    assert apply_suite("par_curve", "dt=x", good, tmp_path).height == 2
    kept = apply_suite("trades_reported", "dt=y", bad, tmp_path)

    assert kept.height == 3 and "BAD" not in kept["isin"].to_list()
    with pytest.raises(DataQualityError):
        apply_suite("trades_reported", "dt=z", bad.with_columns(pl.lit("BAD").alias("isin")), tmp_path)
    assert (tmp_path / "quarantine" / "trades_reported").exists()
    assert len((tmp_path / "results.jsonl").read_text().splitlines()) > 5


def test_lake_paths(tmp_path: Path) -> None:
    lake = Lake(tmp_path)
    key = BronzeKey.for_snapshot("nsdl", "ratings", DAY, "INE053F08403.json")
    assert key.partition_value == "2026-10-14"
    assert lake.read_silver("missing").is_empty()
    shutil.rmtree(tmp_path)
