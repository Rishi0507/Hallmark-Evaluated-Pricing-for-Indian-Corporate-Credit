from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "ingest" / "src"))
sys.path.insert(0, str(ROOT / "analytics" / "spark"))

pyspark = pytest.importorskip("pyspark")

import pandas as pd
from hallmark_ingest.parsers import nsdl, nse
from hallmark_spark.jobs import issuer_liquidity, rating_transitions
from pyspark.sql import SparkSession

FIXTURES = ROOT / "ingest" / "tests" / "fixtures"


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[1]")
        .appName("hallmark-tests")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "2")
        .config("spark.sql.execution.arrow.pyspark.enabled", "true")
        .config("spark.sql.execution.arrow.pyspark.fallback.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


def test_rating_transitions_are_row_stochastic(spark: SparkSession) -> None:
    rows = []
    for path in sorted((FIXTURES / "nsdl").glob("*_ratings.json")):
        rows.extend(nsdl.parse_ratings(path, date(2026, 10, 15)))
    ratings = spark.createDataFrame(pd.DataFrame(rows))
    result = rating_transitions(ratings, date(2025, 10, 1)).collect()
    assert result
    sums: dict[str, float] = {}
    for r in result:
        sums[r["from_bucket"]] = sums.get(r["from_bucket"], 0.0) + r["probability"]
    assert all(abs(s - 1.0) < 1e-9 for s in sums.values())

    assert any(r["from_bucket"] == "AAA" and r["to_bucket"] == "AAA" for r in result)


def test_issuer_liquidity(spark: SparkSession) -> None:
    settled = nse.parse_settlement(
        FIXTURES / "nse" / "cbm_list_non_man_14102026.csv",
        date(2026, 10, 14),
        listing="LISTED",
        counterparty_class="NON_MANDATORY",
        status="SETTLED",
    )
    settlements = spark.createDataFrame(settled.to_pandas())
    instruments = spark.createDataFrame(
        pd.DataFrame(
            [
                nsdl.parse_instrument(p, date(2026, 10, 15))
                for p in sorted((FIXTURES / "nsdl").glob("*_instrument.json"))
            ]
        )
    )
    out = issuer_liquidity(settlements, instruments).collect()
    assert out
    assert all(0 <= r["retail_share"] <= 1 for r in out)
    assert sum(r["isins"] for r in out) >= 20
