from __future__ import annotations

import json
from collections import defaultdict
from typing import Any

import polars as pl
import structlog

from hallmark_ingest.config import Settings
from hallmark_ingest.lake import Lake

log = structlog.get_logger(__name__)

SINK_TOPICS: dict[str, str] = {
    "pricing.marks.v1": "pricing_marks",
    "recon.breaks.v1": "recon_breaks",
    "trades.lifecycle.v1": "trade_lifecycle",
}


def _flatten(dataset: str, envelope: dict[str, Any]) -> dict[str, Any]:
    p = envelope["payload"]
    if dataset == "pricing_marks":
        return {k: v for k, v in p.items() if k != "evidence"} | {
            "evidence": json.dumps(p.get("evidence", []))
        }
    if dataset == "recon_breaks":
        return {k: v for k, v in p.items() if k != "details"} | {
            "details": json.dumps(p.get("details", {})),
            "produced_at": envelope["producedAt"],
        }
    return dict(p) | {"produced_at": envelope["producedAt"]}


def run_sink(settings: Settings, lake: Lake, idle_seconds: float = 20.0) -> dict[str, int]:
    from confluent_kafka import Consumer

    consumer = Consumer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "group.id": "hallmark-lake-sink",
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
    topics = {f"{settings.kafka_topic_prefix}.{t}": d for t, d in SINK_TOPICS.items()}
    consumer.subscribe(list(topics))
    rows: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    idle = 0.0
    try:
        while idle < idle_seconds:
            msg = consumer.poll(1.0)
            if msg is None:
                idle += 1.0
                continue
            if msg.error():
                log.warning("sink.error", error=str(msg.error()))
                continue
            idle = 0.0
            envelope = json.loads(msg.value())
            dataset = topics[msg.topic()]
            day = envelope.get("businessDate") or "undated"
            rows[(dataset, day)].append(_flatten(dataset, envelope))
        written: dict[str, int] = defaultdict(int)
        for (dataset, day), items in sorted(rows.items()):
            frame = pl.DataFrame(items, infer_schema_length=None)
            path = lake.silver_path(dataset, f"dt={day}")
            if path.exists():
                frame = pl.concat([pl.read_parquet(path), frame], how="diagonal_relaxed")
            key = (
                ["breakId", "status"]
                if dataset == "recon_breaks"
                else (
                    ["isin", "valuationDate"]
                    if dataset == "pricing_marks"
                    else ["isin", "tradeDate", "state"]
                )
            )
            frame = frame.unique(subset=key, keep="last")
            lake.write_silver(dataset, f"dt={day}", frame)
            written[dataset] += len(items)
        consumer.commit(asynchronous=False)
        return dict(written)
    finally:
        consumer.close()
