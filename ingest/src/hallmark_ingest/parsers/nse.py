from __future__ import annotations

import csv
import io
import re
from datetime import date
from pathlib import Path

import polars as pl

from hallmark_ingest.parsers import descriptor

SETTLEMENT_SCHEMA: dict[str, pl.DataType] = {
    "settlement_date": pl.Date(),
    "trade_date": pl.Date(),
    "isin": pl.String(),
    "description": pl.String(),
    "instrument_class": pl.String(),
    "listing": pl.String(),
    "counterparty_class": pl.String(),
    "status": pl.String(),
    "quantity": pl.Int64(),
    "nominal_value_inr": pl.Float64(),
    "wap": pl.Float64(),
    "way": pl.Float64(),
    "face_value_inr": pl.Float64(),
    "denomination_class": pl.String(),
}


def _read_csv(path: Path) -> pl.DataFrame:
    text = path.read_bytes().decode("utf-8-sig", errors="replace")
    frame = pl.read_csv(io.StringIO(text), infer_schema_length=0, truncate_ragged_lines=True)
    return frame.rename({c: re.sub(r"\s+", " ", c).strip() for c in frame.columns})


def _to_float(col: str) -> pl.Expr:
    return pl.col(col).str.strip_chars().str.replace_all(",", "").cast(pl.Float64, strict=False)


def _to_date(col: str) -> pl.Expr:

    stripped = pl.col(col).str.strip_chars()
    two_digit_year = stripped.str.contains(r"^\d{1,2}-[A-Za-z]{3}-\d{2}$")
    return (
        pl.when(two_digit_year)
        .then(stripped.str.to_date("%d-%b-%y", strict=False))
        .otherwise(
            pl.coalesce(
                stripped.str.to_date("%d-%b-%Y", strict=False),
                stripped.str.to_date("%Y-%m-%d", strict=False),
            )
        )
    )


def parse_bhavcopy(path: Path, trade_date: date) -> pl.DataFrame:
    raw = _read_csv(path)
    if raw.is_empty():
        return pl.DataFrame()
    cols = {c.lower(): c for c in raw.columns}

    def pick(*needles: str) -> str:
        for lower, original in cols.items():
            if all(n in lower for n in needles):
                return original
        raise KeyError(needles)

    frame = raw.select(
        pl.lit(trade_date).alias("trade_date"),
        pl.col(pick("isin")).str.strip_chars().alias("isin"),
        _to_float(pick("last trade price")).alias("last_price"),
        _to_float(pick("last trade value")).alias("last_trade_value_lakh"),
        _to_float(pick("total trade value")).alias("total_trade_value_lakh"),
        _to_float(pick("last trade yield")).alias("last_yield"),
        _to_float(pick("weighted average price")).alias("wap"),
        _to_float(pick("weighted average yield")).alias("way"),
    )
    return frame.filter(pl.col("isin").str.len_chars() == 12).with_columns(
        pl.col("isin").map_elements(instrument_class, return_dtype=pl.String).alias("instrument_class"),
        pl.lit("NSE").alias("venue"),
    )


def instrument_class(isin: str) -> str:
    if not isin.startswith("IN"):
        return "OTHER"
    code = isin[7:9]
    if code == "14":
        return "CP"
    if code == "16":
        return "CD"
    if isin.startswith("IN00") or isin.startswith("IN2") or isin.startswith("IN1"):
        return "GSEC"
    return "BOND"


def parse_settlement(
    path: Path,
    settlement_date: date,
    *,
    listing: str | None,
    counterparty_class: str | None,
    status: str,
    instrument: str | None = None,
) -> pl.DataFrame:
    raw = _read_csv(path)
    if raw.is_empty() or "ISIN" not in raw.columns:
        return pl.DataFrame(schema=SETTLEMENT_SCHEMA)
    desc_col = "Description" if "Description" in raw.columns else "Descriptor"
    price_col = next(c for c in raw.columns if c.startswith("Weighted Average Price"))
    yield_col = next(c for c in raw.columns if c.startswith("Weighted Average Yield"))
    frame = raw.select(
        pl.lit(settlement_date).alias("settlement_date"),
        _to_date("Trade Date").alias("trade_date"),
        pl.col("ISIN").str.strip_chars().alias("isin"),
        pl.col(desc_col).str.strip_chars().alias("description"),
        pl.col("Quantity").str.strip_chars().cast(pl.Float64, strict=False).cast(pl.Int64).alias("quantity"),
        _to_float("Nominal Value").alias("nominal_value_inr"),
        _to_float(price_col).round(6).alias("wap"),
        _to_float(yield_col).round(6).alias("way"),
    ).filter(pl.col("isin").str.len_chars() == 12)
    face = [descriptor.parse_face_value(d or "") for d in frame["description"].to_list()]
    return frame.with_columns(
        pl.Series("face_value_inr", face, dtype=pl.Float64),
        pl.Series("denomination_class", [descriptor.denomination_class(f) for f in face], dtype=pl.String),
        (
            pl.lit(instrument)
            if instrument
            else pl.col("isin").map_elements(instrument_class, return_dtype=pl.String)
        ).alias("instrument_class"),
        pl.lit(listing, dtype=pl.String).alias("listing"),
        pl.lit(counterparty_class, dtype=pl.String).alias("counterparty_class"),
        pl.lit(status).alias("status"),
    ).select(list(SETTLEMENT_SCHEMA))


def parse_ntrp_master(path: Path, as_of: date) -> pl.DataFrame:
    text = path.read_bytes().decode("utf-8-sig", errors="replace")
    rows = list(csv.reader(io.StringIO(text)))
    start = next(i for i, r in enumerate(rows) if r and r[0].strip().lower() == "sectype")
    header = [re.sub(r"\s+", " ", h).strip() for h in rows[start]]
    body = [r for r in rows[start + 1 :] if len(r) >= len(header) and r[header.index("ISIN")].strip()]
    raw = pl.DataFrame([r[: len(header)] for r in body], schema=header, orient="row")

    def coupon(expr: pl.Expr) -> pl.Expr:
        return expr.str.replace("%", "").str.strip_chars().cast(pl.Float64, strict=False)

    return raw.select(
        pl.lit(as_of).alias("as_of"),
        pl.col("ISIN").str.strip_chars().alias("isin"),
        pl.col("Sectype").str.strip_chars().alias("sec_type"),
        pl.col("Security").str.strip_chars().alias("symbol"),
        pl.col("Issue Desc").str.strip_chars().alias("issue_description"),
        pl.col("Issuer").str.strip_chars().alias("issuer_name"),
        coupon(pl.col("Issue Name")).alias("coupon_rate"),
        _to_float("Face Value").alias("face_value"),
        _to_date("Issue Date").alias("issue_date"),
        _to_date("Maturity Date").alias("maturity_date"),
        pl.col("Coupon Frequency").str.strip_chars().alias("coupon_frequency"),
        _to_date("Next Coupon Date").alias("next_coupon_date"),
        pl.col("Day Count Convention").str.strip_chars().alias("day_count"),
        pl.col("Floating Benchmark").str.strip_chars().alias("floating_benchmark"),
        _to_float("Spread over the Benchmark").alias("floating_spread"),
        _to_date("Last Trade Date").alias("last_trade_date"),
        _to_float("Last trade price").alias("last_trade_price"),
        _to_float("Last Trade Yield").alias("last_trade_yield"),
        pl.col("Status").str.strip_chars().alias("listing_status"),
    ).unique(subset=["isin"], keep="first")
