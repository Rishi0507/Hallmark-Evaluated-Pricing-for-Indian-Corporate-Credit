from __future__ import annotations

import json
import re
from datetime import date, datetime
from pathlib import Path

import openpyxl
import polars as pl


def _sheet_rows(path: Path, name_prefix: str) -> list[tuple[object, ...]]:
    workbook = openpyxl.load_workbook(path, read_only=True, data_only=True)
    try:
        sheet = next(ws for ws in workbook.worksheets if ws.title.lower().startswith(name_prefix.lower()))
        return [tuple(row) for row in sheet.iter_rows(values_only=True)]
    finally:
        workbook.close()


def _as_date(value: object) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    if isinstance(value, str) and value.strip():
        for fmt in ("%d-%b-%Y", "%d-%m-%Y", "%Y-%m-%d"):
            try:
                return datetime.strptime(value.strip(), fmt).date()
            except ValueError:
                continue
    return None


def _as_float(value: object) -> float | None:
    if isinstance(value, int | float):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value.strip())
        except ValueError:
            return None
    return None


def parse_gsec_prices(path: Path, as_of: date) -> pl.DataFrame:
    rows = _sheet_rows(path, "G-Sec")
    out = []
    for row in rows:
        if not row or not isinstance(row[0], str) or not re.fullmatch(r"IN\w{10}", row[0].strip()):
            continue
        maturity = _as_date(row[2])
        price = _as_float(row[3])
        ytm = _as_float(row[4])
        if maturity is None or price is None or ytm is None:
            continue
        remark = str(row[5]).strip().upper() if len(row) > 5 and row[5] else ""
        out.append(
            {
                "as_of": as_of,
                "isin": row[0].strip(),
                "coupon_rate": _as_float(row[1]),
                "maturity_date": maturity,
                "price": price,
                "ytm_semiannual": ytm,
                "security_kind": "FRB" if remark == "FRB" else ("IIB" if "IIB" in remark else "FIXED"),
                "curve_input": remark == "INPUT POINT",
                "traded": len(row) > 7 and str(row[7] or "").strip().upper() == "T",
            }
        )
    return pl.DataFrame(
        out,
        schema={
            "as_of": pl.Date,
            "isin": pl.String,
            "coupon_rate": pl.Float64,
            "maturity_date": pl.Date,
            "price": pl.Float64,
            "ytm_semiannual": pl.Float64,
            "security_kind": pl.String,
            "curve_input": pl.Boolean,
            "traded": pl.Boolean,
        },
    )


def parse_par_curve(path: Path, as_of: date) -> pl.DataFrame:
    rows = _sheet_rows(path, "Par Yield")
    out = []
    for row in rows:
        tenor = _as_float(row[0]) if row else None
        semi = _as_float(row[1]) if row and len(row) > 1 else None
        if tenor is None or semi is None or not 0 < tenor <= 60:
            continue
        annual = _as_float(row[2]) if len(row) > 2 else None
        out.append(
            {
                "as_of": as_of,
                "tenor_years": tenor,
                "par_yield_semiannual": semi,
                "par_yield_annualised": annual,
            }
        )
    return pl.DataFrame(
        out,
        schema={
            "as_of": pl.Date,
            "tenor_years": pl.Float64,
            "par_yield_semiannual": pl.Float64,
            "par_yield_annualised": pl.Float64,
        },
    )


_TENOR = re.compile(r"(\d+)\s*(Day|Days|Month|Months)", re.IGNORECASE)


def _tenor_days(name: str) -> int | None:
    match = _TENOR.search(name)
    if not match:
        return None
    n = int(match.group(1))
    return n if match.group(2).lower().startswith("day") else round(n * 365 / 12)


def parse_tbill_curve(paths: list[Path]) -> pl.DataFrame:
    records: dict[tuple[date, str], dict[str, object]] = {}
    for path in sorted(paths, key=lambda p: p.stat().st_mtime):
        for item in json.loads(path.read_text(encoding="utf-8")):
            day = _as_date(str(item["processRunDate"])[:10])
            tenor = str(item["tenorName"]).strip()
            if day is None:
                continue
            records[(day, tenor)] = {
                "as_of": day,
                "tenor": tenor,
                "tenor_days": _tenor_days(tenor),
                "yield_pct": float(item["rate"]),
            }
    return pl.DataFrame(
        list(records.values()),
        schema={
            "as_of": pl.Date,
            "tenor": pl.String,
            "tenor_days": pl.Int64,
            "yield_pct": pl.Float64,
        },
    ).sort(["as_of", "tenor_days"])


def parse_fx_reference(paths: list[Path]) -> pl.DataFrame:
    records: dict[tuple[date, str], dict[str, object]] = {}
    for path in sorted(paths, key=lambda p: p.stat().st_mtime):
        for item in json.loads(path.read_text(encoding="utf-8")):
            day = _as_date(str(item["processRunDate"])[:10])
            name = str(item["subProdName"]).strip()
            match = re.match(r"INR\s*/\s*(\d+)\s*([A-Z]{3})", name)
            if day is None or not match:
                continue
            units, ccy = int(match.group(1)), match.group(2)
            records[(day, ccy)] = {
                "as_of": day,
                "currency": ccy,
                "units": units,
                "inr_per_unit": float(item["rate"]) / units,
            }
    return pl.DataFrame(
        list(records.values()),
        schema={
            "as_of": pl.Date,
            "currency": pl.String,
            "units": pl.Int64,
            "inr_per_unit": pl.Float64,
        },
    ).sort(["as_of", "currency"])
