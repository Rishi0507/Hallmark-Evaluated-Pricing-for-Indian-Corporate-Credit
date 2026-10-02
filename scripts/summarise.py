from __future__ import annotations

import sys
from pathlib import Path

import duckdb

ROOT = Path(__file__).resolve().parents[1]
B = (ROOT / "data" / "exports" / "backfill").as_posix()
S = (ROOT / "data" / "silver").as_posix()


def show(con: duckdb.DuckDBPyConnection, title: str, sql: str) -> None:
    print(f"\n== {title}")
    print(con.execute(sql).pl())


def main() -> None:
    con = duckdb.connect()
    con.execute(f"create view bt as select * from read_csv_auto('{B}/backtest.csv')")
    con.execute(f"create view marks as select * from read_csv_auto('{B}/marks.csv', quote='\"', escape='\"')")
    con.execute(
        f"create view recon as select * from read_csv_auto('{B}/recon_outcomes.csv', quote='\"', escape='\"')"
    )
    con.execute(
        f"create view yr as select * from read_csv_auto('{B}/recon_yield.csv', quote='\"', escape='\"')"
    )
    con.execute(f"create view settle as select * from read_parquet('{S}/settlements/*/*.parquet')")
    first = sys.argv[1] if len(sys.argv) > 1 else "2026-05-01"
    show(
        con,
        "backtest by method",
        f"""
        select method, count(*) n, round(avg(abs(error_bps)),1) mae, round(median(abs(error_bps)),1) mdae,
               round(quantile_cont(abs(error_bps),0.9),1) p90, round(avg(error_bps),1) bias,
               round(avg((abs(error_bps)<=25)::int),3) within25
        from bt where trade_date >= '{first}' group by method order by n desc""",
    )
    show(
        con,
        "model vs naive baselines (instruments with a prior trade)",
        f"""
        select count(*) n, round(avg(abs(error_bps)),1) model_mae, round(median(abs(error_bps)),1) model_mdae,
               round(avg(abs(last_yield_error_bps)),1) last_yield_mae, round(median(abs(last_yield_error_bps)),1) last_yield_mdae,
               round(avg(abs(carried_spread_error_bps)),1) carried_spread_mae,
               round(median(abs(carried_spread_error_bps)),1) carried_spread_mdae
        from bt where trade_date >= '{first}' and last_yield_error_bps is not null""",
    )
    show(
        con,
        "backtest by rating bucket",
        f"""
        select rating_bucket, count(*) n, round(median(abs(error_bps)),1) mdae, round(avg(abs(error_bps)),1) mae
        from bt where trade_date >= '{first}' group by 1 order by 2 desc""",
    )
    show(
        con,
        "calibration by confidence",
        f"""
        select case when confidence < 0.4 then '<0.4' when confidence < 0.6 then '0.4-0.6'
                    when confidence < 0.8 then '0.6-0.8' else '>=0.8' end band,
               count(*) n, round(median(abs(error_bps)),1) mdae
        from bt where trade_date >= '{first}' group by 1 order by 1""",
    )
    show(
        con,
        "method mix on the last valuation date",
        """
        select method, count(*) n, round(avg(confidence),2) conf from marks
        where valuation_date = (select max(valuation_date) from marks) group by 1 order by 2 desc""",
    )
    show(
        con,
        "marks per day",
        "select count(*)/count(distinct valuation_date) avg_marks_per_day, count(distinct isin) isins from marks",
    )
    show(
        con,
        "trade vs settlement (trade dates with the window closed)",
        """
        select result, coalesce(break_type,'-') break_type, count(*) n, round(sum(amount_lakh)/100,1) crore
        from recon where recon_type='TRADE_VS_SETTLEMENT' and business_date <= date '2026-10-06'
        group by 1,2 order by 3 desc""",
    )
    show(
        con,
        "yield recompute",
        """
        select result, coalesce(break_type,'-') break_type, count(*) n from yr group by 1,2 order by 3 desc""",
    )
    show(
        con,
        "refdata sources",
        """
        select result, coalesce(break_type,'-') break_type, count(*) n from recon
        where recon_type='REFDATA_SOURCES' group by 1,2 order by 3 desc""",
    )
    show(
        con,
        "settled bond flow by counterparty and denomination (Apr-Oct 2026)",
        """
        select counterparty_class, denomination_class, count(*) legs, round(sum(nominal_value_inr)/1e7,0) crore
        from settle where instrument_class='BOND' and status='SETTLED' and settlement_date >= '2026-04-01'
        group by 1,2 order by 4 desc""",
    )


if __name__ == "__main__":
    main()
