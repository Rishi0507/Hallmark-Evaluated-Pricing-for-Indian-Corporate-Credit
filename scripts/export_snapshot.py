from __future__ import annotations

import json
import math
import os
from datetime import date, datetime
from pathlib import Path
from typing import Any

import duckdb

ROOT = Path(__file__).resolve().parents[1]
WAREHOUSE = ROOT / "data" / "warehouse.duckdb"
SILVER = (ROOT / "data" / "silver").as_posix()
OUT = ROOT / "web" / "public" / "data"
TEST_FROM = "2026-07-01"
BUCKETS = ["AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB", "SUB_IG"]
TENORS = [0.5, 1, 2, 3, 5, 7, 10, 15]


def clean(value: Any) -> Any:
    if isinstance(value, float):
        return None if math.isnan(value) or math.isinf(value) else round(value, 4)
    if isinstance(value, datetime | date):
        return value.isoformat()[:10]
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return [clean(v) for v in value]
    return value


def rows(con: duckdb.DuckDBPyConnection, sql: str) -> list[dict[str, Any]]:
    cur = con.execute(sql)
    names = [d[0] for d in cur.description]
    return [clean(dict(zip(names, r, strict=True))) for r in cur.fetchall()]


def one(con: duckdb.DuckDBPyConnection, sql: str) -> dict[str, Any]:
    return rows(con, sql)[0]


def write(name: str, payload: Any) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
    print(f"{name}.json {(OUT / f'{name}.json').stat().st_size / 1024:.0f} KB")


def error_stats(con: duckdb.DuckDBPyConnection, column: str, where: str) -> dict[str, Any]:
    return one(
        con,
        f"""
        select count(*) n, avg(abs({column})) mae, median(abs({column})) median_abs,
               quantile_cont(abs({column}), 0.9) p90, avg({column}) bias,
               avg(case when abs({column}) <= 25 then 1 else 0 end) within_25
        from stg_backtest where trade_date >= '{TEST_FROM}' and {column} is not null and {where}""",
    )


def main() -> None:
    os.chdir(ROOT / "warehouse")
    con = duckdb.connect(str(WAREHOUSE), read_only=True)
    as_of = one(con, "select max(valuation_date) d from stg_marks")["d"]

    write(
        "overview",
        {
            "asOf": as_of,
            "testFrom": TEST_FROM,
            "coverage": one(
                con,
                f"""
            select (select count(*) from read_parquet('{SILVER}/trades_reported/*/*.parquet')) reported_rows,
                   (select count(*) from read_parquet('{SILVER}/settlements/*/*.parquet')) settlement_rows,
                   (select min(trade_date) from read_parquet('{SILVER}/trades_reported/*/*.parquet')) first_date,
                   (select max(trade_date) from read_parquet('{SILVER}/trades_reported/*/*.parquet')) last_date,
                   (select count(distinct as_of) from read_parquet('{SILVER}/par_curve/*/*.parquet')) curve_days,
                   (select count(*) from dim_security) securities,
                   (select count(*) from dim_security where pricing_eligible) priceable,
                   (select count(distinct valuation_date) from stg_marks) valuation_days,
                   (select count(*) / count(distinct valuation_date) from stg_marks) marks_per_day""",
            ),
            "methodMix": rows(
                con,
                f"""
            select method, count(*) n, avg(confidence) avg_confidence from stg_marks
            where valuation_date = '{as_of}' group by 1 order by 2 desc""",
            ),
            "backtest": {
                "byMethod": rows(
                    con,
                    f"""
                select method, count(*) n, avg(abs(error_bps)) mae, median(abs(error_bps)) median_abs,
                       avg(case when abs(error_bps) <= 25 then 1 else 0 end) within_25
                from stg_backtest where trade_date >= '{TEST_FROM}' group by 1 order by 2 desc""",
                ),
                "model": error_stats(con, "error_bps", "last_yield_error_bps is not null"),
                "lastTradedYield": error_stats(con, "last_yield_error_bps", "true"),
                "lastSpreadCarried": error_stats(
                    con, "carried_spread_error_bps", "last_yield_error_bps is not null"
                ),
            },
            "recon": rows(con, "select * from fct_recon_summary order by recon_type, outcomes desc"),
        },
    )

    write("market", rows(con, "select * from fct_daily_market order by business_date"))

    fits = rows(con, f"select * from stg_curve_fits where valuation_date = '{as_of}'")
    params = {f["bucket"]: f for f in fits}
    curves = []
    for bucket in BUCKETS:
        if bucket not in params:
            continue
        points = []
        for t in TENORS:
            running = max(
                params[b]["level_bps"] + params[b]["slope_bps"] * math.log1p(t)
                for b in BUCKETS[: BUCKETS.index(bucket) + 1]
                if b in params
            )
            points.append({"tenor": t, "spreadBps": round(running, 1)})
        curves.append(
            {
                "bucket": bucket,
                "source": params[bucket]["source"],
                "effectiveN": params[bucket]["effective_n"],
                "points": points,
            }
        )
    write(
        "curves",
        {
            "asOf": as_of,
            "credit": curves,
            "riskFree": rows(
                con,
                f"""
            select tenor_years tenor, par_yield_annualised par_yield from stg_par_curve
            where as_of = (select max(as_of) from stg_par_curve where as_of <= '{as_of}') and tenor_years <= 30
            order by 1""",
            ),
            "history": rows(
                con,
                """
            select valuation_date, bucket, level_bps + slope_bps * ln(1 + 3) spread_3y
            from stg_curve_fits where bucket in ('AAA', 'AA', 'A', 'BBB') order by 1, 2""",
            ),
        },
    )

    write(
        "marks",
        rows(
            con,
            """
        select isin, issuer_name, method, clean_price, yield_pct, z_spread_bps, g_spread_bps,
               modified_duration, tenor_years, rating_bucket, sector_group, denomination_class, confidence,
               model_spread_bps, evidence
        from fct_marks_latest order by z_spread_bps desc""",
        ),
    )

    write(
        "backtest",
        {
            "monthly": rows(con, "select * from fct_backtest_monthly order by month, method"),
            "byBucket": rows(
                con,
                f"""
            select rating_bucket, count(*) n, median(abs(error_bps)) median_abs, avg(abs(error_bps)) mae
            from stg_backtest where trade_date >= '{TEST_FROM}' group by 1 order by 2 desc""",
            ),
            "calibration": rows(
                con,
                f"""
            select case when confidence < 0.4 then '< 0.4' when confidence < 0.6 then '0.4 to 0.6'
                        when confidence < 0.8 then '0.6 to 0.8' else '0.8 and above' end band,
                   count(*) n, median(abs(error_bps)) median_abs, avg(abs(error_bps)) mae
            from stg_backtest where trade_date >= '{TEST_FROM}' group by 1 order by 1""",
            ),
            "errorHistogram": rows(
                con,
                f"""
            select greatest(-100, least(100, round(error_bps / 5) * 5)) bin, count(*) n
            from stg_backtest where trade_date >= '{TEST_FROM}' group by 1 order by 1""",
            ),
        },
    )

    write(
        "recon",
        {
            "summary": rows(con, "select * from fct_recon_summary order by recon_type, outcomes desc"),
            "daily": rows(
                con,
                """
            select business_date, result, count(*) n from stg_recon_outcomes
            where recon_type = 'TRADE_VS_SETTLEMENT'
              and coalesce(json_extract_string(details, '$.instrumentClass'), 'BOND') = 'BOND'
            group by 1, 2 order by 1""",
            ),
            "breaks": rows(
                con,
                """
            select o.recon_type, o.isin, d.issuer_name, o.business_date, o.break_type, o.severity, o.amount_lakh,
                   o.details
            from stg_recon_outcomes o left join dim_security d on d.isin = o.isin
            where o.result = 'BREAK' and o.recon_type = 'TRADE_VS_SETTLEMENT'
              and coalesce(json_extract_string(o.details, '$.instrumentClass'), 'BOND') = 'BOND'
            order by o.business_date desc, o.amount_lakh desc limit 150""",
            ),
            "yieldBreaks": rows(
                con,
                """
            select y.isin, d.issuer_name, y.trade_date, y.result, y.break_type, y.face_lakh, y.details
            from stg_recon_yield y left join dim_security d on d.isin = y.isin
            where y.result <> 'MATCHED' order by y.trade_date desc, y.face_lakh desc limit 150""",
            ),
        },
    )

    write(
        "distribution",
        {
            "monthly": rows(
                con,
                """
            select month, denomination_class, counterparty_class, sum(settled_face_crore) face_crore,
                   sum(settlement_legs) legs,
                   sum(settled_face_crore * value_weighted_yield) / nullif(sum(settled_face_crore), 0) yield
            from fct_distribution_monthly group by 1, 2, 3 order by 1, 2, 3""",
            ),
            "retailByRating": rows(
                con,
                """
            select rating_bucket, sum(settled_face_crore) face_crore, sum(settlement_legs) legs,
                   count(distinct month) as active_months,
                   sum(settled_face_crore * value_weighted_yield) / nullif(sum(settled_face_crore), 0) yield
            from fct_distribution_monthly where denomination_class = 'RETAIL' and month >= date '2026-01-01'
            group by 1 order by 2 desc""",
            ),
            "watchlist": rows(
                con,
                """
            select * from fct_retail_watchlist
            order by negative_rating_signals + downgrades_365d desc, retail_face_crore_90d desc limit 60""",
            ),
            "ratingActions": rows(
                con, "select * from fct_rating_actions_monthly order by month, action_type"
            ),
        },
    )


if __name__ == "__main__":
    main()
