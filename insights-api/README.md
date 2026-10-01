# insights-api

Read-only FastAPI service over the dbt marts in the DuckDB warehouse.

| Endpoint | Source mart |
|---|---|
| `GET /health` | |
| `GET /api/v1/market/daily` | `fct_daily_market` |
| `GET /api/v1/distribution/monthly` | `fct_distribution_monthly` |
| `GET /api/v1/distribution/retail-share` | `fct_distribution_monthly` |
| `GET /api/v1/distribution/watchlist` | `fct_retail_watchlist` |
| `GET /api/v1/backtest/monthly` | `fct_backtest_monthly` |
| `GET /api/v1/recon/summary` | `fct_recon_summary` |
| `GET /api/v1/securities/{isin}` | `dim_security` |

OpenAPI documentation is served at `/docs`.

```bash
pip install -e ".[dev]"
INSIGHTS_WAREHOUSE_PATH=../data/warehouse.duckdb uvicorn insights_api.app:app --port 8090
pytest
```

The warehouse is injected through `create_app`, so the tests run against an in-memory DuckDB loaded from the dashboard snapshot in `web/public/data`.
