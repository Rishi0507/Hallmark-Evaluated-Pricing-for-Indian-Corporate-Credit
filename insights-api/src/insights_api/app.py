import threading
from datetime import date
from typing import Annotated, Any

import duckdb
from fastapi import Depends, FastAPI, HTTPException, Query, Request
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="INSIGHTS_")

    warehouse_path: str = "data/warehouse.duckdb"


class Warehouse:
    def __init__(self, connection: duckdb.DuckDBPyConnection) -> None:
        self._con = connection
        self._lock = threading.Lock()

    @classmethod
    def open(cls, path: str) -> "Warehouse":
        return cls(duckdb.connect(path, read_only=True))

    def query(self, sql: str, params: list[Any] | None = None) -> list[dict[str, Any]]:
        with self._lock:
            cur = self._con.execute(sql, params or [])
            names = [d[0] for d in cur.description]
            return [dict(zip(names, row, strict=True)) for row in cur.fetchall()]


def get_warehouse(request: Request) -> Warehouse:
    if getattr(request.app.state, "warehouse", None) is None:
        request.app.state.warehouse = Warehouse.open(Settings().warehouse_path)
    return request.app.state.warehouse


Wh = Annotated[Warehouse, Depends(get_warehouse)]


def create_app(warehouse: Warehouse | None = None) -> FastAPI:
    app = FastAPI(
        title="Hallmark insights",
        version="0.1.0",
        description="Distribution, market, backtest and reconciliation analytics over public Indian bond data.",
    )
    app.state.warehouse = warehouse

    @app.get("/health")
    def health(wh: Wh) -> dict[str, Any]:
        latest = wh.query("select max(business_date) as d from fct_daily_market")[0]["d"]
        return {"status": "UP", "latestBusinessDate": latest}

    @app.get("/api/v1/market/daily")
    def market_daily(
        wh: Wh,
        start: Annotated[date | None, Query(alias="from")] = None,
        end: Annotated[date | None, Query(alias="to")] = None,
    ) -> list[dict[str, Any]]:
        return wh.query(
            "select * from fct_daily_market where business_date between ? and ? order by business_date",
            [start or date(1900, 1, 1), end or date(2999, 12, 31)],
        )

    @app.get("/api/v1/distribution/monthly")
    def distribution_monthly(
        wh: Wh,
        denomination: Annotated[str | None, Query(pattern="^(RETAIL|HNI|INSTITUTIONAL|UNKNOWN)$")] = None,
    ) -> list[dict[str, Any]]:
        sql = "select * from fct_distribution_monthly"
        params: list[Any] = []
        if denomination:
            sql += " where denomination_class = ?"
            params.append(denomination)
        return wh.query(sql + " order by month, denomination_class, counterparty_class", params)

    @app.get("/api/v1/distribution/retail-share")
    def retail_share(wh: Wh) -> list[dict[str, Any]]:
        return wh.query(
            """
            select month,
                   sum(case when denomination_class = 'RETAIL' then settled_face_crore else 0 end) as retail_crore,
                   sum(settled_face_crore) as total_crore,
                   sum(case when denomination_class = 'RETAIL' then settled_face_crore else 0 end)
                       / nullif(sum(settled_face_crore), 0) as retail_share
            from fct_distribution_monthly group by month order by month
            """
        )

    @app.get("/api/v1/distribution/watchlist")
    def watchlist(wh: Wh, limit: Annotated[int, Query(ge=1, le=500)] = 50) -> list[dict[str, Any]]:
        return wh.query(
            """
            select * from fct_retail_watchlist
            order by negative_rating_signals + downgrades_365d desc, retail_face_crore_90d desc limit ?
            """,
            [limit],
        )

    @app.get("/api/v1/backtest/monthly")
    def backtest_monthly(wh: Wh) -> list[dict[str, Any]]:
        return wh.query("select * from fct_backtest_monthly order by month, method")

    @app.get("/api/v1/recon/summary")
    def recon_summary(
        wh: Wh, recon_type: Annotated[str | None, Query(alias="reconType")] = None
    ) -> list[dict[str, Any]]:
        sql = "select * from fct_recon_summary"
        params: list[Any] = []
        if recon_type:
            sql += " where recon_type = ?"
            params.append(recon_type)
        return wh.query(sql + " order by recon_type, outcomes desc", params)

    @app.get("/api/v1/securities/{isin}")
    def security(isin: str, wh: Wh) -> dict[str, Any]:
        rows = wh.query("select * from dim_security where isin = ?", [isin.upper()])
        if not rows:
            raise HTTPException(status_code=404, detail=f"unknown ISIN {isin}")
        return rows[0]

    return app


app = create_app()
