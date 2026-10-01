from __future__ import annotations

import json
import tempfile
from pathlib import Path

import duckdb
import pytest
from fastapi.testclient import TestClient

from insights_api.app import Warehouse, create_app

SNAPSHOT = Path(__file__).resolve().parents[2] / "web" / "public" / "data"


def _table(con: duckdb.DuckDBPyConnection, name: str, rows: list[dict]) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / f"{name}.json"
        path.write_text(json.dumps(rows), encoding="utf-8")
        con.execute(f"create table {name} as select * from read_json_auto('{path.as_posix()}')")


@pytest.fixture(scope="module")
def client() -> TestClient:
    con = duckdb.connect()
    market = json.loads((SNAPSHOT / "market.json").read_text())
    dist = json.loads((SNAPSHOT / "distribution.json").read_text())
    backtest = json.loads((SNAPSHOT / "backtest.json").read_text())
    recon = json.loads((SNAPSHOT / "recon.json").read_text())
    marks = json.loads((SNAPSHOT / "marks.json").read_text())
    _table(con, "fct_daily_market", market)
    _table(
        con,
        "fct_distribution_monthly",
        [r | {"settled_face_crore": r["face_crore"]} for r in dist["monthly"]],
    )
    _table(con, "fct_retail_watchlist", dist["watchlist"])
    _table(con, "fct_backtest_monthly", backtest["monthly"])
    _table(con, "fct_recon_summary", recon["summary"])
    _table(con, "dim_security", [{"isin": m["isin"], "issuer_name": m["issuer_name"]} for m in marks])
    return TestClient(create_app(Warehouse(con)))


def test_health_reports_latest_date(client: TestClient) -> None:
    body = client.get("/health").json()
    assert body["status"] == "UP" and body["latestBusinessDate"].startswith("2026-10")


def test_market_range(client: TestClient) -> None:
    rows = client.get("/api/v1/market/daily", params={"from": "2026-10-01", "to": "2026-10-14"}).json()
    assert 5 <= len(rows) <= 12
    assert all("2026-10" in r["business_date"] for r in rows)


def test_distribution_and_retail_share(client: TestClient) -> None:
    retail = client.get("/api/v1/distribution/monthly", params={"denomination": "RETAIL"}).json()
    assert retail and all(r["denomination_class"] == "RETAIL" for r in retail)
    share = client.get("/api/v1/distribution/retail-share").json()
    assert all(0 <= r["retail_share"] < 0.5 for r in share)
    assert client.get("/api/v1/distribution/monthly", params={"denomination": "OTHER"}).status_code == 422


def test_watchlist_backtest_recon(client: TestClient) -> None:
    assert len(client.get("/api/v1/distribution/watchlist", params={"limit": 5}).json()) == 5
    assert client.get("/api/v1/backtest/monthly").json()
    rows = client.get("/api/v1/recon/summary", params={"reconType": "YIELD_RECOMPUTE"}).json()
    assert rows and {r["recon_type"] for r in rows} == {"YIELD_RECOMPUTE"}


def test_security_lookup(client: TestClient) -> None:
    isin = client.get("/api/v1/distribution/watchlist").json()[0]
    assert isin
    marks = json.loads((SNAPSHOT / "marks.json").read_text())
    assert client.get(f"/api/v1/securities/{marks[0]['isin'].lower()}").status_code == 200
    assert client.get("/api/v1/securities/INE000000000").status_code == 404
