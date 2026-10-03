# warehouse

dbt project over the silver lake and the service outputs.

## Models

| Layer | Model | Grain |
|---|---|---|
| Staging | `stg_trades_reported`, `stg_settlements`, `stg_par_curve`, `stg_fx_reference`, `stg_nsdl_ratings` | One row per source record |
| Staging | `stg_golden`, `stg_marks`, `stg_observations`, `stg_backtest`, `stg_curve_fits`, `stg_recon_outcomes`, `stg_recon_yield` | One row per service output |
| Mart | `dim_security` | ISIN |
| Mart | `fct_daily_market` | Business date |
| Mart | `fct_distribution_monthly` | Month, denomination, counterparty, rating bucket, sector |
| Mart | `fct_retail_watchlist` | Issuer |
| Mart | `fct_rating_actions_monthly` | Month, action type (upgrade, downgrade, withdrawal, new, reaffirmation or other) |
| Mart | `fct_backtest_monthly` | Month, method |
| Mart | `fct_recon_summary` | Control, instrument class, result, break type |
| Mart | `fct_marks_latest` | ISIN, latest valuation date |

The `silver()` macro resolves a dataset name to `read_parquet` over the lake on DuckDB and to a staged table on Snowflake.

## Run

```bash
cp profiles.example.yml profiles.yml
dbt build --profiles-dir .                    # DuckDB, writes data/warehouse.duckdb
dbt build --profiles-dir . --target snowflake # needs SNOWFLAKE_* variables
```

`profiles.yml` is ignored by git.
