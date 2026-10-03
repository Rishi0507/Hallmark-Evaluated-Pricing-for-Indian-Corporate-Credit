from __future__ import annotations

from pathlib import Path

import duckdb
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
B = (ROOT / "data" / "exports" / "backfill").as_posix()
S = (ROOT / "data" / "silver").as_posix()

con = duckdb.connect()
pairs = con.execute(f"""
with obs as (
  select isin, trade_date, yield_pct, tenor_years
  from read_csv_auto('{B}/observations.csv', quote='"', escape='"')
  where size_class = 'INSTITUTIONAL' and z_spread_bps is not null
),
seq as (
  select isin, trade_date, yield_pct, tenor_years,
         lag(trade_date) over w prev_date, lag(yield_pct) over w prev_yield
  from obs window w as (partition by isin order by trade_date)
),
curve as (select as_of, tenor_years, par_yield_annualised p from read_parquet('{S}/par_curve/*/*.parquet'))
select s.trade_date, s.yield_pct - s.prev_yield dy,
       c1.p - c0.p drf, datediff('day', s.prev_date, s.trade_date) gap
from seq s
join curve c1 on c1.as_of = s.trade_date and c1.tenor_years = greatest(0.25, round(s.tenor_years * 4) / 4)
join curve c0 on c0.as_of = s.prev_date and c0.tenor_years = greatest(0.25, round(s.tenor_years * 4) / 4)
where s.prev_date is not null and datediff('day', s.prev_date, s.trade_date) <= 30
""").fetchnumpy()

for label, mask in (
    ("Apr-Jun (fit)", pairs["trade_date"] < np.datetime64("2026-07-01")),
    ("Jul-Oct (test)", pairs["trade_date"] >= np.datetime64("2026-07-01")),
):
    dy = pairs["dy"][mask]
    drf = pairs["drf"][mask]
    beta = float(np.sum(dy * drf) / np.sum(drf * drf))
    print(f"{label}: n={mask.sum()} beta={beta:.3f} corr={np.corrcoef(dy, drf)[0, 1]:.3f}")
    for b in (0.0, 0.25, beta, 0.5, 1.0):
        print(
            f"   beta={b:.2f}: median abs error {np.median(np.abs(dy - b * drf)) * 100:.2f} bp,"
            f" mean {np.mean(np.abs(dy - b * drf)) * 100:.2f} bp"
        )
