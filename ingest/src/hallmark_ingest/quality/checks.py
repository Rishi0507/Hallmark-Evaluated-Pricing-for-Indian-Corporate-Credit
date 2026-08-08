from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

import polars as pl


@dataclass(frozen=True)
class Expectation:
    name: str
    predicate: Callable[[pl.DataFrame], pl.Series]
    blocking: bool = False


@dataclass(frozen=True)
class CheckResult:
    dataset: str
    partition: str
    expectation: str
    blocking: bool
    rows: int
    failed: int
    sample: list[str]
    checked_at: str

    @property
    def passed(self) -> bool:
        return self.failed == 0


class DataQualityError(RuntimeError):
    pass


def _col_between(col: str, lo: float, hi: float) -> Callable[[pl.DataFrame], pl.Series]:
    return lambda df: df[col].is_null() | df[col].is_between(lo, hi)


def _not_null(*cols: str) -> Callable[[pl.DataFrame], pl.Series]:
    def check(df: pl.DataFrame) -> pl.Series:
        mask = pl.Series([True] * df.height)
        for c in cols:
            mask = mask & df[c].is_not_null()
        return mask

    return check


def _unique(*cols: str) -> Callable[[pl.DataFrame], pl.Series]:
    return lambda df: ~df.select(list(cols)).is_duplicated()


def _isin_format(col: str = "isin") -> Callable[[pl.DataFrame], pl.Series]:
    return lambda df: df[col].str.contains(r"^IN[A-Z0-9]{9}[0-9]$")


SUITES: dict[str, list[Expectation]] = {
    "trades_reported": [
        Expectation("isin_format", _isin_format(), blocking=True),
        Expectation("unique_isin_per_day", _unique("trade_date", "isin"), blocking=True),
        Expectation("wap_present", _not_null("wap")),
        Expectation("wap_range_0_1000", _col_between("wap", 0.01, 1000.0)),
        Expectation("way_range_minus5_60", _col_between("way", -5.0, 60.0)),
        Expectation("value_positive", _col_between("total_trade_value_lakh", 0.0001, 1e9)),
    ],
    "settlements": [
        Expectation("isin_format", _isin_format(), blocking=True),
        Expectation("trade_date_present", _not_null("trade_date"), blocking=True),
        Expectation(
            "trade_not_after_settlement",
            lambda df: df["trade_date"] <= df["settlement_date"],
            blocking=True,
        ),
        Expectation(
            "settlement_within_30d",
            lambda df: (df["settlement_date"] - df["trade_date"]).dt.total_days() <= 30,
        ),
        Expectation("nominal_positive", _col_between("nominal_value_inr", 1.0, 1e13)),
        Expectation("wap_range_0_1000", _col_between("wap", 0.01, 1000.0)),
        Expectation("way_range_minus5_60", _col_between("way", -5.0, 60.0)),
        Expectation(
            "face_value_parsed",
            lambda df: (df["instrument_class"] != "BOND") | df["face_value_inr"].is_not_null(),
        ),
    ],
    "gsec_prices": [
        Expectation("isin_format", _isin_format(), blocking=True),
        Expectation("unique_isin", _unique("isin"), blocking=True),
        Expectation("ytm_range_0_20", _col_between("ytm_semiannual", 0.0, 20.0)),
    ],
    "par_curve": [
        Expectation("unique_tenor", _unique("tenor_years"), blocking=True),
        Expectation("yield_range_0_20", _col_between("par_yield_semiannual", 0.0, 20.0)),
    ],
    "ntrp_master": [
        Expectation("isin_format", _isin_format(), blocking=True),
        Expectation("unique_isin", _unique("isin"), blocking=True),
    ],
    "nsdl_instrument": [
        Expectation("isin_format", _isin_format(), blocking=True),
    ],
    "nsdl_cashflows": [
        Expectation("amount_positive", _col_between("amount_per_unit", 0.0, 1e10)),
        Expectation("due_date_present", _not_null("due_date"), blocking=True),
    ],
    "nsdl_ratings": [
        Expectation("agency_present", _not_null("agency")),
        Expectation("symbol_mapped", lambda df: df["withdrawn"] | df["rating_symbol"].is_not_null()),
    ],
}


MAX_QUARANTINE_SHARE = 0.25


def apply_suite(dataset: str, partition: str, frame: pl.DataFrame, dq_dir: Path) -> pl.DataFrame:
    suite = SUITES.get(dataset, [])
    results: list[CheckResult] = []
    now = datetime.now(UTC).isoformat()
    keep = pl.Series([True] * frame.height, dtype=pl.Boolean)
    for exp in suite:
        if frame.is_empty():
            results.append(CheckResult(dataset, partition, exp.name, exp.blocking, 0, 0, [], now))
            continue
        passed = exp.predicate(frame).fill_null(False)
        if exp.blocking:
            keep = keep & passed
        failed_rows = frame.filter(~passed)
        sample_col = "isin" if "isin" in frame.columns else frame.columns[0]
        results.append(
            CheckResult(
                dataset=dataset,
                partition=partition,
                expectation=exp.name,
                blocking=exp.blocking,
                rows=frame.height,
                failed=failed_rows.height,
                sample=[str(v) for v in failed_rows[sample_col].head(5).to_list()],
                checked_at=now,
            )
        )
    dq_dir.mkdir(parents=True, exist_ok=True)
    with (dq_dir / "results.jsonl").open("a", encoding="utf-8") as fh:
        for r in results:
            fh.write(json.dumps(asdict(r), default=str) + "\n")
    if frame.is_empty():
        return frame
    quarantined = frame.filter(~keep)
    if quarantined.height:
        target = dq_dir / "quarantine" / dataset / f"{partition}.parquet"
        target.parent.mkdir(parents=True, exist_ok=True)
        quarantined.write_parquet(target)
    if quarantined.height > MAX_QUARANTINE_SHARE * frame.height:
        failed = [r for r in results if r.blocking and not r.passed]
        raise DataQualityError(
            f"{dataset}/{partition}: "
            + ", ".join(f"{r.expectation} failed for {r.failed}/{r.rows} rows" for r in failed)
        )
    return frame.filter(keep)
