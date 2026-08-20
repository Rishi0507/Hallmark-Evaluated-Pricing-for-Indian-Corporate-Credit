from __future__ import annotations

import hashlib
import json
import math
from collections.abc import Iterator
from datetime import UTC, date, datetime
from typing import Any

import duckdb
import polars as pl

from hallmark_ingest.lake import Lake

TOPICS: dict[str, str] = {
    "SecurityMasterRecord": "refdata.source.v1",
    "MarketCurve": "market.curve.v1",
    "ReportedTrade": "trades.reported.v1",
    "SettlementRecord": "trades.settlement.v1",
    "EndOfDay": "control.eod.v1",
}

SOURCE = "hallmark-ingest"


def _jsonable(value: Any) -> Any:
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return [_jsonable(v) for v in value]
    return value


def make_event(
    event_type: str, key: str, business_date: date | None, payload: dict[str, Any]
) -> dict[str, Any]:
    clean = _jsonable(payload)
    canonical = json.dumps(
        {"t": event_type, "k": key, "d": business_date.isoformat() if business_date else None, "p": clean},
        sort_keys=True,
        separators=(",", ":"),
    )
    return {
        "eventId": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        "eventType": event_type,
        "schemaVersion": 1,
        "source": SOURCE,
        "key": key,
        "businessDate": business_date.isoformat() if business_date else None,
        "producedAt": datetime.now(UTC).isoformat(),
        "payload": clean,
    }


def _partition_frame(lake: Lake, dataset: str, partition: str) -> pl.DataFrame:
    path = lake.silver_path(dataset, partition)
    return pl.read_parquet(path) if path.exists() else pl.DataFrame()


def curve_event(lake: Lake, day: date, tbill: pl.DataFrame | None = None) -> dict[str, Any] | None:
    curve = _partition_frame(lake, "par_curve", f"dt={day.isoformat()}")
    if curve.is_empty():
        return None
    points = [
        {"tenorYears": r["tenor_years"], "parYield": r["par_yield_semiannual"]}
        for r in curve.sort("tenor_years").iter_rows(named=True)
    ]
    bills: list[dict[str, Any]] = []
    if tbill is not None and not tbill.is_empty():
        bills = [
            {"tenorDays": r["tenor_days"], "yield": r["yield_pct"]}
            for r in tbill.filter(pl.col("as_of") == day).sort("tenor_days").iter_rows(named=True)
            if r["tenor_days"] is not None
        ]
    payload = {
        "asOfDate": day,
        "source": "FBIL",
        "curveType": "GSEC_PAR",
        "compounding": "SEMI_ANNUAL",
        "points": points,
        "tbill": bills,
    }
    return make_event("MarketCurve", f"GSEC_PAR:{day.isoformat()}", day, payload)


def reported_events(lake: Lake, day: date) -> list[dict[str, Any]]:
    frame = _partition_frame(lake, "trades_reported", f"dt={day.isoformat()}")
    events = []
    for r in frame.iter_rows(named=True):
        payload = {
            "isin": r["isin"],
            "tradeDate": r["trade_date"],
            "venue": "NSE",
            "instrumentClass": r["instrument_class"],
            "lastPrice": r["last_price"],
            "lastYield": r["last_yield"],
            "lastTradeValueLakh": r["last_trade_value_lakh"],
            "totalTradeValueLakh": r["total_trade_value_lakh"] or 0.0,
            "wap": r["wap"],
            "way": r["way"],
        }
        events.append(make_event("ReportedTrade", r["isin"], r["trade_date"], payload))
    return events


def settlement_events(lake: Lake, day: date) -> list[dict[str, Any]]:
    frame = _partition_frame(lake, "settlements", f"dt={day.isoformat()}")
    events = []
    for r in frame.iter_rows(named=True):
        payload = {
            "isin": r["isin"],
            "description": r["description"],
            "tradeDate": r["trade_date"],
            "settlementDate": r["settlement_date"],
            "instrumentClass": r["instrument_class"],
            "listing": r["listing"],
            "counterpartyClass": r["counterparty_class"],
            "status": r["status"],
            "quantity": r["quantity"],
            "nominalValueInr": r["nominal_value_inr"] or 0.0,
            "wap": r["wap"],
            "way": r["way"],
            "faceValueInr": r["face_value_inr"],
            "denominationClass": r["denomination_class"],
        }
        events.append(make_event("SettlementRecord", r["isin"], r["settlement_date"], payload))
    return events


def eod_event(day: date, counts: dict[str, int], curve_available: bool) -> dict[str, Any]:
    payload = {"businessDate": day, "counts": counts, "curveAvailable": curve_available}
    return make_event("EndOfDay", day.isoformat(), day, payload)


def business_days(lake: Lake, start: date, end: date) -> list[date]:
    days: set[date] = set()
    for dataset in ("trades_reported", "settlements", "par_curve"):
        for part in lake.silver_partitions(dataset):
            day = date.fromisoformat(part.split("=", 1)[1])
            if start <= day <= end:
                days.add(day)
    return sorted(days)


def _latest_per_isin(lake: Lake, dataset: str) -> pl.DataFrame:
    if not lake.silver_partitions(dataset):
        return pl.DataFrame()
    with duckdb.connect() as con:
        return con.execute(
            f"""
            select * from read_parquet('{lake.silver_glob(dataset)}')
            qualify snapshot = max(snapshot) over (partition by isin)
            """
        ).pl()


def security_events(lake: Lake, isins: set[str] | None = None) -> Iterator[dict[str, Any]]:
    instrument = _latest_per_isin(lake, "nsdl_instrument")
    ratings = _latest_per_isin(lake, "nsdl_ratings")
    terms = _latest_per_isin(lake, "nsdl_terms")
    flows = _latest_per_isin(lake, "nsdl_cashflows")
    redemption = _latest_per_isin(lake, "nsdl_redemption")

    def by_isin(frame: pl.DataFrame) -> dict[str, list[dict[str, Any]]]:
        out: dict[str, list[dict[str, Any]]] = {}
        if frame.is_empty():
            return out
        for row in frame.iter_rows(named=True):
            out.setdefault(row["isin"], []).append(row)
        return out

    rating_map, flow_map = by_isin(ratings), by_isin(flows)
    terms_map = {k: v[0] for k, v in by_isin(terms).items()}
    red_map = {k: v[0] for k, v in by_isin(redemption).items()}

    from hallmark_ingest.parsers.descriptor import parse as parse_descriptor

    for inst in instrument.iter_rows(named=True) if not instrument.is_empty() else []:
        isin = inst["isin"]
        if not isin or (isins is not None and isin not in isins):
            continue
        t = terms_map.get(isin, {})
        red = red_map.get(isin, {})
        desc = parse_descriptor(inst["description"] or "")
        principal = [f for f in flow_map.get(isin, []) if f["is_principal"]]
        maturity = max((f["payment_date"] for f in principal), default=None) or desc.maturity
        payload = {
            "isin": isin,
            "source": "NSDL",
            "snapshotDate": inst["snapshot"],
            "description": inst["description"],
            "issuerName": inst["issuer_name"],
            "cin": inst["cin"],
            "lei": inst["lei"],
            "ownership": inst["ownership"],
            "issuerNature": inst["issuer_nature"],
            "sector": inst["sector"],
            "industry": inst["industry"],
            "basicIndustry": inst["basic_industry"],
            "instrumentType": inst["instrument_type"],
            "couponRate": t.get("coupon_rate") if t else desc.coupon,
            "couponBasis": t.get("coupon_basis"),
            "paymentFrequency": t.get("payment_frequency"),
            "dayCount": t.get("day_count"),
            "maturityDate": maturity,
            "issueDate": None,
            "faceValue": desc.face_value,
            "listingStatus": None,
            "stepUp": t.get("step_up"),
            "stepUpTerms": t.get("step_up_terms"),
            "scheduleWarnings": sorted((t.get("schedule_warnings") or "").split(","))
            if t.get("schedule_warnings")
            else [],
            "firstCallDate": red.get("first_call_date"),
            "firstPutDate": red.get("first_put_date"),
            "ratings": [
                {
                    "agency": r["agency"],
                    "rawRating": r["rating_raw"],
                    "symbol": r["rating_symbol"],
                    "scale": r["rating_scale"],
                    "notch": r["rating_notch"],
                    "outlook": r["outlook"],
                    "action": r["action"],
                    "ratingDate": r["rating_date"],
                    "changeDate": r["change_date"],
                    "watch": r["watch"],
                    "isCurrent": r["is_current"],
                    "creditEnhanced": r["credit_enhanced"],
                    "structured": r["structured"],
                    "provisional": r["provisional"],
                    "withdrawn": r["withdrawn"],
                }
                for r in rating_map.get(isin, [])
            ],
            "cashflows": [
                {
                    "paymentDate": f["payment_date"],
                    "dueDate": f["due_date"],
                    "amountPerUnit": f["amount_per_unit"],
                    "principal": f["is_principal"],
                    "event": f["event"],
                }
                for f in flow_map.get(isin, [])
            ],
        }
        yield make_event("SecurityMasterRecord", isin, inst["snapshot"], payload)

    if lake.silver_partitions("ntrp_master"):
        latest = lake.silver_partitions("ntrp_master")[-1]
        master = _partition_frame(lake, "ntrp_master", latest)
        for r in master.iter_rows(named=True):
            if isins is not None and r["isin"] not in isins:
                continue
            payload = {
                "isin": r["isin"],
                "source": "NSE_NTRP",
                "snapshotDate": r["as_of"],
                "description": r["issue_description"],
                "issuerName": r["issuer_name"],
                "couponRate": r["coupon_rate"],
                "paymentFrequency": r["coupon_frequency"],
                "dayCount": r["day_count"],
                "maturityDate": r["maturity_date"],
                "issueDate": r["issue_date"],
                "faceValue": r["face_value"],
                "listingStatus": r["listing_status"],
                "ratings": [],
                "cashflows": [],
            }
            yield make_event("SecurityMasterRecord", r["isin"], r["as_of"], payload)
