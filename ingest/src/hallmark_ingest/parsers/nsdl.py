from __future__ import annotations

import json
import re
from datetime import date, datetime
from pathlib import Path
from typing import Any

import polars as pl

from hallmark_ingest.parsers import ratings as rating_scale

MIN_PLAUSIBLE_YEAR = 1950
_DATE_IN_TEXT = re.compile(r"(\d{2})[/-](\d{2})[/-](\d{4})")


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _clean(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return None if text in {"", "-", "NA", "null", "None"} else text


def _date(value: Any) -> date | None:
    text = _clean(value)
    if text is None:
        return None
    for fmt in ("%d-%m-%Y", "%d/%m/%Y", "%Y-%m-%d"):
        try:
            parsed = datetime.strptime(text, fmt).date()
        except ValueError:
            continue

        return parsed if parsed.year >= MIN_PLAUSIBLE_YEAR else None
    return None


def _float(value: Any) -> float | None:
    text = _clean(value)
    if text is None:
        return None
    try:
        return float(text.replace(",", ""))
    except ValueError:
        return None


INSTRUMENT_SCHEMA: dict[str, pl.DataType] = {
    "snapshot": pl.Date(),
    "isin": pl.String(),
    "description": pl.String(),
    "security_type": pl.String(),
    "issuer_name": pl.String(),
    "cin": pl.String(),
    "lei": pl.String(),
    "ownership": pl.String(),
    "issuer_nature": pl.String(),
    "macro_sector": pl.String(),
    "sector": pl.String(),
    "industry": pl.String(),
    "basic_industry": pl.String(),
    "instrument_type": pl.String(),
}


def parse_instrument(path: Path, snapshot: date) -> dict[str, Any]:
    data = _load(path)
    owner = _clean(data.get("issuerTypeOwner"))
    return {
        "snapshot": snapshot,
        "isin": data["isin"],
        "description": _clean(data.get("secType")),
        "security_type": _clean(data.get("secTypeDesc")),
        "issuer_name": _clean(data.get("issuerName")),
        "cin": _clean(data.get("cin")),
        "lei": _clean(data.get("lei")),
        "ownership": "PSU"
        if owner and "PSU" in owner and "Non" not in owner
        else ("PRIVATE" if owner else None),
        "issuer_nature": _clean(data.get("issuerTypeNature")),
        "macro_sector": _clean(data.get("macro")),
        "sector": _clean(data.get("sector")),
        "industry": _clean(data.get("industry")),
        "basic_industry": _clean(data.get("basicIndusrty")),
        "instrument_type": _clean(data.get("instrumentType")),
    }


RATING_SCHEMA: dict[str, pl.DataType] = {
    "snapshot": pl.Date(),
    "isin": pl.String(),
    "agency": pl.String(),
    "rating_raw": pl.String(),
    "rating_symbol": pl.String(),
    "rating_scale": pl.String(),
    "rating_notch": pl.Int64(),
    "credit_enhanced": pl.Boolean(),
    "structured": pl.Boolean(),
    "provisional": pl.Boolean(),
    "withdrawn": pl.Boolean(),
    "outlook": pl.String(),
    "action": pl.String(),
    "rating_date": pl.Date(),
    "change_date": pl.Date(),
    "watch": pl.String(),
    "is_current": pl.Boolean(),
    "press_release": pl.String(),
}

_AGENCIES = {
    "CRISIL": "CRISIL",
    "ICRA": "ICRA",
    "CARE": "CARE",
    "INDIA RATING": "IND-RA",
    "ACUITE": "ACUITE",
    "BRICKWORK": "BRICKWORK",
    "INFOMERICS": "INFOMERICS",
}


def agency_code(name: str | None) -> str | None:
    if not name:
        return None
    upper = name.upper()
    return next((code for key, code in _AGENCIES.items() if key in upper), upper)


def parse_ratings(path: Path, snapshot: date) -> list[dict[str, Any]]:
    data = _load(path)
    rows: list[dict[str, Any]] = []
    for is_current, block in (
        (True, data.get("currentRatings") or []),
        (False, data.get("earlierRatings") or []),
    ):
        for item in block:
            raw = _clean(item.get("currentRating")) or ""
            norm = rating_scale.normalise(raw)
            rows.append(
                {
                    "snapshot": snapshot,
                    "isin": item.get("isin"),
                    "agency": agency_code(_clean(item.get("creditRatingAgencyName"))),
                    "rating_raw": raw,
                    "rating_symbol": norm.symbol,
                    "rating_scale": norm.scale,
                    "rating_notch": norm.notch,
                    "credit_enhanced": norm.credit_enhanced,
                    "structured": norm.structured,
                    "provisional": norm.provisional or _clean(item.get("provisionalFlag")) == "Y",
                    "withdrawn": norm.withdrawn,
                    "outlook": _clean(item.get("outlook")),
                    "action": _clean(item.get("ratingAction")),
                    "rating_date": _date(item.get("creditRatingDate")),
                    "change_date": _date(item.get("ratingChangeDate")),
                    "watch": _clean(item.get("iciWatch")),
                    "is_current": is_current,
                    "press_release": _clean(item.get("pressReleaseLink")),
                }
            )
    return rows


TERMS_SCHEMA: dict[str, pl.DataType] = {
    "snapshot": pl.Date(),
    "isin": pl.String(),
    "coupon_basis": pl.String(),
    "coupon_rate": pl.Float64(),
    "coupon_text": pl.String(),
    "payment_frequency": pl.String(),
    "day_count": pl.String(),
    "benchmark_index": pl.String(),
    "step_up": pl.Boolean(),
    "step_up_terms": pl.String(),
    "schedule_warnings": pl.String(),
}

CASHFLOW_SCHEMA: dict[str, pl.DataType] = {
    "snapshot": pl.Date(),
    "isin": pl.String(),
    "event": pl.String(),
    "is_principal": pl.Boolean(),
    "record_date": pl.Date(),
    "due_date": pl.Date(),
    "payment_date": pl.Date(),
    "amount_per_unit": pl.Float64(),
    "actual_payment_date": pl.Date(),
    "delay_reason": pl.String(),
}


def parse_cashflows(path: Path, snapshot: date) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    data = _load(path).get("coupensVo") or {}
    details = data.get("couponDetails") or {}
    schedule = (data.get("cashFlowScheduleDetails") or {}).get("cashFlowSchedule") or []
    isin = next((row.get("isin") for row in schedule if row.get("isin")), None)
    step = data.get("stepStatus") or {}
    step_terms = "; ".join(
        filter(None, (_clean(s.get("otherDetailsStepUp")) for s in (step.get("stepUp") or [])))
    )
    basics = details.get("coupenBasicVo") or [{}]
    terms = None
    if isin:
        coupon_text = _clean(details.get("couponRate"))
        terms = {
            "snapshot": snapshot,
            "isin": isin,
            "coupon_basis": _clean(details.get("couponBasis")),
            "coupon_rate": _float(coupon_text),
            "coupon_text": coupon_text,
            "payment_frequency": _clean(details.get("interestPaymentFrequency")),
            "day_count": _clean(details.get("dayCountConvention")),
            "benchmark_index": _clean(basics[0].get("benchmarkIndex")) if basics else None,
            "step_up": _clean(step.get("stepUpAvail")) == "Yes",
            "step_up_terms": step_terms or None,
        }
    flows: dict[tuple[str, date, float], dict[str, Any]] = {}
    warnings: set[str] = set()
    for row in schedule:
        event = _clean(row.get("cashFlowsEvent"))
        due = _date(row.get("dueDate"))
        amount = row.get("amountPayable")
        if event is not None and due is not None and amount is None:
            warnings.add("MISSING_AMOUNT")
        if event is None or due is None or amount is None:
            continue
        amount_f = float(amount)
        key = (event, due, round(amount_f, 4))
        flows[key] = {
            "snapshot": snapshot,
            "isin": row.get("isin") or isin,
            "event": event,
            "is_principal": "REDEMPTION" in event.upper(),
            "record_date": _date(row.get("recordDate")),
            "due_date": due,
            "payment_date": _date(row.get("paymentDate")) or due,
            "amount_per_unit": amount_f,
            "actual_payment_date": _date(row.get("actualPaymentDate")),
            "delay_reason": _clean(row.get("reasonDelayPayment")),
        }
    ordered = sorted(flows.values(), key=lambda r: (r["payment_date"], not r["is_principal"]))
    if terms is not None:
        warnings |= schedule_warnings(terms, ordered, snapshot)
        terms["schedule_warnings"] = ",".join(sorted(warnings)) or None
    return terms, ordered


CRITICAL_SCHEDULE_WARNINGS = frozenset(
    {"MISSING_AMOUNT", "ZERO_INTEREST_AMOUNTS", "COUPON_AMOUNT_INCONSISTENT"}
)


def schedule_warnings(terms: dict[str, Any], flows: list[dict[str, Any]], snapshot: date) -> set[str]:
    out: set[str] = set()
    rate = terms.get("coupon_rate")
    basis = (terms.get("coupon_basis") or "").lower()
    interest = [f for f in flows if not f["is_principal"]]
    principal = [f for f in flows if f["is_principal"]]
    if not principal:
        out.add("NO_PRINCIPAL")
    if "fixed" not in basis or not rate or rate <= 0 or not interest:
        return out
    if all(f["amount_per_unit"] == 0 for f in interest):
        out.add("ZERO_INTEREST_AMOUNTS")
        return out
    upcoming = [f for f in interest if f["payment_date"] > snapshot and f["amount_per_unit"] > 0]
    if not upcoming:
        return out
    nxt = upcoming[0]
    previous = [f["payment_date"] for f in interest if f["payment_date"] < nxt["payment_date"]]
    if not previous:
        return out
    days = (nxt["payment_date"] - max(previous)).days
    outstanding = sum(f["amount_per_unit"] for f in principal if f["payment_date"] >= nxt["payment_date"])
    if days < 20 or outstanding <= 0:
        return out
    implied = nxt["amount_per_unit"] / outstanding * 365.0 / days * 100.0
    if abs(implied / rate - 1.0) > 0.35:
        out.add("COUPON_AMOUNT_INCONSISTENT")
    return out


REDEMPTION_SCHEMA: dict[str, pl.DataType] = {
    "snapshot": pl.Date(),
    "isin": pl.String(),
    "redemption_type": pl.String(),
    "units_outstanding": pl.Float64(),
    "first_call_date": pl.Date(),
    "first_put_date": pl.Date(),
    "call_terms": pl.String(),
    "put_terms": pl.String(),
    "defaulted_redemption": pl.String(),
}


def _first_date(*texts: str | None) -> date | None:
    found: list[date] = []
    for text in texts:
        for d, m, y in _DATE_IN_TEXT.findall(text or ""):
            try:
                found.append(date(int(y), int(m), int(d)))
            except ValueError:
                continue
    return min(found) if found else None


def parse_redemption(path: Path, snapshot: date, isin: str) -> dict[str, Any]:
    data = _load(path)
    call = data.get("callOption") or {}
    put = data.get("putOption") or {}
    call_terms = _clean(call.get("specifiedDates"))
    put_terms = _clean(put.get("specifiedDates"))
    return {
        "snapshot": snapshot,
        "isin": isin,
        "redemption_type": _clean(data.get("redemptionType")),
        "units_outstanding": _float(data.get("netQuantity")),
        "first_call_date": _date(call.get("deadlineDate")) or _first_date(call_terms),
        "first_put_date": _date(put.get("deadlineDate")) or _first_date(put_terms),
        "call_terms": call_terms,
        "put_terms": put_terms,
        "defaulted_redemption": _clean(data.get("defaultedRedemption")),
    }
