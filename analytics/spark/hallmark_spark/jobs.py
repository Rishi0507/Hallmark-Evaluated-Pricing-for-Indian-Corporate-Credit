from __future__ import annotations

import argparse
from datetime import date, timedelta

from pyspark.sql import DataFrame, SparkSession, Window
from pyspark.sql import functions as F

BUCKET_ORDER = ["AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB", "SUB_IG", "D", "WITHDRAWN"]


def bucket(notch: F.Column) -> F.Column:
    return (
        F.when(notch.isNull(), F.lit("WITHDRAWN"))
        .when(notch == 1, "AAA")
        .when(notch == 2, "AA+")
        .when(notch == 3, "AA")
        .when(notch == 4, "AA-")
        .when(notch == 5, "A+")
        .when(notch == 6, "A")
        .when(notch == 7, "A-")
        .when(notch <= 10, "BBB")
        .when(notch < 20, "SUB_IG")
        .otherwise("D")
    )


def latest_ratings(ratings: DataFrame) -> DataFrame:
    w = Window.partitionBy("isin")
    return (
        ratings.withColumn("latest", F.max("snapshot").over(w))
        .where(
            (F.col("snapshot") == F.col("latest"))
            & (F.col("rating_scale").isNull() | (F.col("rating_scale") == "LONG"))
        )
        .withColumn("effective_date", F.coalesce("change_date", "rating_date"))
        .where(F.col("effective_date").isNotNull() & F.col("agency").isNotNull())
        .select("isin", "agency", "effective_date", "rating_notch", "withdrawn")
    )


def rating_on(history: DataFrame, as_of: date, label: str) -> DataFrame:
    w = Window.partitionBy("isin", "agency").orderBy(F.col("effective_date").desc())
    return (
        history.where(F.col("effective_date") <= F.lit(as_of))
        .withColumn("rn", F.row_number().over(w))
        .where("rn = 1")
        .select(
            "isin",
            "agency",
            bucket(F.when(F.col("withdrawn"), F.lit(None)).otherwise(F.col("rating_notch"))).alias(label),
        )
    )


def rating_transitions(ratings: DataFrame, cohort: date, horizon_days: int = 365) -> DataFrame:
    history = latest_ratings(ratings)
    start = rating_on(history, cohort, "from_bucket").where(F.col("from_bucket") != "WITHDRAWN")
    end = rating_on(history, cohort + timedelta(days=horizon_days), "to_bucket")
    pairs = start.join(end, ["isin", "agency"], "left").fillna({"to_bucket": "WITHDRAWN"})
    counts = pairs.groupBy("from_bucket", "to_bucket").agg(F.count("*").alias("instruments"))
    totals = Window.partitionBy("from_bucket")
    return (
        counts.withColumn("probability", F.col("instruments") / F.sum("instruments").over(totals))
        .withColumn("cohort", F.lit(cohort))
        .withColumn("horizon_days", F.lit(horizon_days))
    )


def issuer_liquidity(settlements: DataFrame, instruments: DataFrame) -> DataFrame:
    issuers = instruments.select("isin", F.coalesce("cin", "issuer_name").alias("issuer_key"), "issuer_name")
    bonds = settlements.where((F.col("instrument_class") == "BOND") & (F.col("status") == "SETTLED"))
    return (
        bonds.join(F.broadcast(issuers.dropDuplicates(["isin"])), "isin", "left")
        .withColumn("month", F.trunc("settlement_date", "month"))
        .groupBy("issuer_key", "month")
        .agg(
            F.first("issuer_name", ignorenulls=True).alias("issuer_name"),
            F.countDistinct("settlement_date").alias("settlement_days"),
            F.countDistinct("isin").alias("isins"),
            (F.sum("nominal_value_inr") / 1e7).alias("face_crore"),
            (
                F.sum(
                    F.when(F.col("denomination_class") == "RETAIL", F.col("nominal_value_inr")).otherwise(0)
                )
                / F.sum("nominal_value_inr")
            ).alias("retail_share"),
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--silver", default="data/silver")
    parser.add_argument("--out", default="data/gold/spark")
    parser.add_argument("--cohort", default="2025-10-01")
    args = parser.parse_args()
    spark = SparkSession.builder.appName("hallmark-batch").getOrCreate()
    ratings = spark.read.parquet(f"{args.silver}/nsdl_ratings")
    rating_transitions(ratings, date.fromisoformat(args.cohort)).write.mode("overwrite").parquet(
        f"{args.out}/rating_transitions"
    )
    settlements = spark.read.parquet(f"{args.silver}/settlements")
    instruments = spark.read.parquet(f"{args.silver}/nsdl_instrument")
    issuer_liquidity(settlements, instruments).write.mode("overwrite").partitionBy("month").parquet(
        f"{args.out}/issuer_liquidity"
    )
    spark.stop()


if __name__ == "__main__":
    main()
