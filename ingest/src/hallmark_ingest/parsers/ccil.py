from __future__ import annotations

import json
import re
from datetime import date, datetime
from pathlib import Path

import polars as pl

_GSEC_NAME = re.compile(r"^(\d{1,2}\.\d{2})\s+GS\s+(\d{4})")


def parse_ndsom(path: Path, trade_date: date, captured_at: datetime) -> pl.DataFrame:
    payload = json.loads(path.read_text(encoding="utf-8"))
    items = json.loads(payload.get("result1") or "[]")
    rows = []
    for item in items:
        name = str(item.get("ismt_idnt", "")).strip()
        match = _GSEC_NAME.match(name)
        rows.append(
            {
                "trade_date": trade_date,
                "captured_at": captured_at,
                "security": name,
                "coupon_rate": float(match.group(1)) if match else None,
                "maturity_year": int(match.group(2)) if match else None,
                "market": item.get("mrkt_indc"),
                "book": item.get("book_indc"),
                "trade_count": int(item.get("ttc") or 0),
                "traded_amount_cr": float(item.get("tta") or 0),
                "open_price": float(item.get("op") or 0),
                "high_price": float(item.get("hi") or 0),
                "low_price": float(item.get("lo") or 0),
                "last_price": float(item.get("ltp") or 0),
                "last_yield": float(item.get("lty")) if item.get("lty") is not None else None,
            }
        )
    return pl.DataFrame(rows)


def parse_far_limits(path: Path, as_of: date) -> pl.DataFrame:
    payload = json.loads(path.read_text(encoding="utf-8"))
    items = json.loads(payload.get("result") or "[]")
    return pl.DataFrame(
        [
            {
                "as_of": as_of,
                "instrument": item.get("dlmt_ismt_indc"),
                "route": item.get("dlmt_pool_indc"),
                "limit_cr": item.get("dlmt_absx_limt"),
                "investment_cr": item.get("dlmt_totl_ivst_qnty"),
                "utilisation_pct": item.get("dlmt_limt_util_pect"),
                "vrr_utilisation_cr": item.get("dlmt_totl_vrr_util"),
            }
            for item in items
        ]
    )
