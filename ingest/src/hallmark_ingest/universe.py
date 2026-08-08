from __future__ import annotations

from datetime import date

import duckdb

from hallmark_ingest.lake import Lake


def active_isins(lake: Lake, *, since: date | None = None, limit: int | None = None) -> list[str]:
    sources = []
    if lake.silver_partitions("settlements"):
        sources.append(
            f"select isin, trade_date as d, nominal_value_inr as v from read_parquet('{lake.silver_glob('settlements')}') "
            "where instrument_class = 'BOND'"
        )
    if lake.silver_partitions("trades_reported"):
        sources.append(
            f"select isin, trade_date as d, total_trade_value_lakh * 1e5 as v "
            f"from read_parquet('{lake.silver_glob('trades_reported')}') where instrument_class = 'BOND'"
        )
    if not sources:
        return []
    where = f"where d >= DATE '{since.isoformat()}'" if since else ""
    sql = (
        f"select isin, sum(v) as value from ({' union all '.join(sources)}) {where} "
        "group by isin order by value desc nulls last, isin"
    )
    if limit:
        sql += f" limit {int(limit)}"
    with duckdb.connect() as con:
        return [row[0] for row in con.execute(sql).fetchall()]
