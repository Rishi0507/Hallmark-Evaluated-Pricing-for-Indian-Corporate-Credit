select date_trunc('month', trade_date) as month,
       method,
       count(*) as predictions,
       avg(abs(error_bps)) as mae_bps,
       median(abs(error_bps)) as median_abs_error_bps,
       avg(error_bps) as bias_bps,
       avg(abs(last_yield_error_bps)) as last_yield_mae_bps,
       avg(abs(carried_spread_error_bps)) as carried_spread_mae_bps
from {{ ref('stg_backtest') }}
group by 1, 2
