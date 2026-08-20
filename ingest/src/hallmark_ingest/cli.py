from __future__ import annotations

import json
import logging
from datetime import date, timedelta
from pathlib import Path
from typing import Annotated
from zoneinfo import ZoneInfo

import structlog
import typer

from hallmark_ingest.config import get_settings
from hallmark_ingest.lake import Lake

app = typer.Typer(no_args_is_help=True, add_completion=False, help="Hallmark ingestion pipeline")

structlog.configure(wrapper_class=structlog.make_filtering_bound_logger(logging.INFO))

DateOpt = Annotated[str | None, typer.Option(help="ISO date (YYYY-MM-DD)")]


def _today_ist() -> date:
    from datetime import datetime

    return datetime.now(ZoneInfo("Asia/Kolkata")).date()


def _resolve(value: str | None, default: date) -> date:
    return date.fromisoformat(value) if value else default


def _lake(data_dir: Path | None) -> Lake:
    settings = get_settings()
    return Lake(data_dir or settings.data_dir)


DataDirOpt = Annotated[Path | None, typer.Option(help="Override HALLMARK_DATA_DIR")]


@app.command()
def acquire(
    start: DateOpt = None,
    end: DateOpt = None,
    data_dir: DataDirOpt = None,
) -> None:
    from hallmark_ingest.acquire import acquire_market

    lake = _lake(data_dir)
    end_d = _resolve(end, _today_ist())
    start_d = _resolve(start, end_d - timedelta(days=7))
    stats = acquire_market(get_settings(), lake, start_d, end_d)
    typer.echo(json.dumps(stats, indent=2))


@app.command("acquire-nsdl")
def acquire_nsdl_cmd(
    data_dir: DataDirOpt = None,
    limit: Annotated[int | None, typer.Option(help="Only the N most active ISINs")] = None,
    since: DateOpt = None,
) -> None:
    from hallmark_ingest.acquire import acquire_nsdl, make_client
    from hallmark_ingest.universe import active_isins

    lake = _lake(data_dir)
    isins = active_isins(lake, since=date.fromisoformat(since) if since else None, limit=limit)
    typer.echo(f"{len(isins)} ISINs to enrich")
    with make_client(get_settings()) as client:
        stats = acquire_nsdl(client, lake, isins, _today_ist())
    typer.echo(json.dumps(dict(stats), indent=2))


@app.command()
def transform(
    data_dir: DataDirOpt = None,
    rebuild: Annotated[bool, typer.Option(help="Rebuild every silver partition")] = False,
) -> None:
    from hallmark_ingest.transform import transform_all

    typer.echo(json.dumps(transform_all(_lake(data_dir), rebuild=rebuild), indent=2))


@app.command()
def publish(
    start: DateOpt = None,
    end: DateOpt = None,
    data_dir: DataDirOpt = None,
    sink: Annotated[str, typer.Option(help="kafka or file")] = "kafka",
    out: Annotated[Path | None, typer.Option(help="Output directory for the file sink")] = None,
    refdata: Annotated[bool, typer.Option(help="Publish reference data before market data")] = True,
    validate: Annotated[bool, typer.Option(help="Validate every event against contracts/schemas")] = False,
) -> None:
    from hallmark_ingest.publish import FileSink, KafkaSink, publish_days, publish_refdata

    lake = _lake(data_dir)
    end_d = _resolve(end, _today_ist())
    start_d = _resolve(start, end_d - timedelta(days=7))
    target = (
        FileSink(out or lake.root / "exports" / "events") if sink == "file" else KafkaSink(get_settings())
    )
    stats: dict[str, int] = {}
    if refdata:
        stats.update(publish_refdata(lake, target, validate=validate))
    stats.update(publish_days(lake, target, start_d, end_d, validate=validate))
    typer.echo(json.dumps(stats, indent=2))


@app.command()
def daily(
    day: DateOpt = None,
    data_dir: DataDirOpt = None,
    publish_to: Annotated[str | None, typer.Option(help="kafka, file or omit to skip publishing")] = None,
) -> None:
    from hallmark_ingest.acquire import acquire_ccil, acquire_market, acquire_nsdl, make_client
    from hallmark_ingest.publish import FileSink, KafkaSink, publish_days, publish_refdata
    from hallmark_ingest.transform import transform_all
    from hallmark_ingest.universe import active_isins

    settings = get_settings()
    lake = _lake(data_dir)
    end_d = _resolve(day, _today_ist())
    acquire_market(settings, lake, end_d - timedelta(days=7), end_d)
    transform_all(lake)
    with make_client(settings) as client:
        acquire_nsdl(client, lake, active_isins(lake, since=end_d - timedelta(days=30)), _today_ist())
        if end_d == _today_ist():
            acquire_ccil(client, lake)
    transform_all(lake)
    if publish_to:
        target = FileSink(lake.root / "exports" / "events") if publish_to == "file" else KafkaSink(settings)
        publish_refdata(lake, target)
        publish_days(lake, target, end_d - timedelta(days=7), end_d)


@app.command()
def sink(
    data_dir: DataDirOpt = None,
    idle_seconds: Annotated[float, typer.Option(help="Stop after this many idle seconds")] = 20.0,
) -> None:
    from hallmark_ingest.sink import run_sink

    typer.echo(json.dumps(run_sink(get_settings(), _lake(data_dir), idle_seconds), indent=2))


@app.command("load-outputs")
def load_outputs_cmd(
    source: Annotated[Path, typer.Option(help="Directory written by the batch replay")] = Path(
        "data/exports/backfill"
    ),
    data_dir: DataDirOpt = None,
) -> None:
    from hallmark_ingest.outputs import load_outputs

    typer.echo(json.dumps(load_outputs(_lake(data_dir), source), indent=2))


@app.command("acquire-ccil")
def acquire_ccil_cmd(data_dir: DataDirOpt = None) -> None:
    from hallmark_ingest.acquire import acquire_ccil, make_client

    with make_client(get_settings()) as client:
        typer.echo(json.dumps(dict(acquire_ccil(client, _lake(data_dir))), indent=2))


if __name__ == "__main__":
    app()
