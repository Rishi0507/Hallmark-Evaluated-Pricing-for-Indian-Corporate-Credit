from __future__ import annotations

import json
import os
from collections import Counter
from collections.abc import Iterable
from datetime import date
from pathlib import Path
from typing import Any, Protocol

import jsonschema
import structlog

from hallmark_ingest import events as ev
from hallmark_ingest.config import Settings
from hallmark_ingest.lake import Lake

log = structlog.get_logger(__name__)

SCHEMA_DIR = Path(
    os.environ.get("HALLMARK_SCHEMA_DIR", Path(__file__).resolve().parents[3] / "contracts" / "schemas")
)


class Sink(Protocol):
    def send(self, topic: str, key: str, value: dict[str, Any]) -> None: ...
    def flush(self) -> None: ...


class FileSink:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self._handles: dict[str, Any] = {}

    def send(self, topic: str, key: str, value: dict[str, Any]) -> None:
        if topic not in self._handles:
            self._handles[topic] = (self.root / f"{topic}.jsonl").open("a", encoding="utf-8")
        self._handles[topic].write(json.dumps(value, separators=(",", ":")) + "\n")

    def flush(self) -> None:
        for handle in self._handles.values():
            handle.flush()

    def close(self) -> None:
        for handle in self._handles.values():
            handle.close()


class KafkaSink:
    def __init__(self, settings: Settings) -> None:
        from confluent_kafka import Producer

        self._prefix = settings.kafka_topic_prefix
        self._producer = Producer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "client.id": settings.kafka_client_id,
                "enable.idempotence": True,
                "acks": "all",
                "compression.type": "zstd",
                "linger.ms": 20,
                "batch.num.messages": 5000,
            }
        )
        self._errors = 0

    def _on_delivery(self, err: Any, msg: Any) -> None:
        if err is not None:
            self._errors += 1
            log.error("kafka.delivery_failed", error=str(err), topic=msg.topic())

    def send(self, topic: str, key: str, value: dict[str, Any]) -> None:
        payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
        while True:
            try:
                self._producer.produce(
                    f"{self._prefix}.{topic}",
                    key=key.encode("utf-8"),
                    value=payload,
                    on_delivery=self._on_delivery,
                )
                break
            except BufferError:
                self._producer.poll(0.5)
        self._producer.poll(0)

    def flush(self) -> None:
        remaining = self._producer.flush(60)
        if remaining or self._errors:
            raise RuntimeError(f"kafka publish incomplete: {remaining} pending, {self._errors} failed")


class SchemaValidator:
    def __init__(self, schema_dir: Path = SCHEMA_DIR) -> None:
        self._validators = {
            p.stem: jsonschema.Draft202012Validator(
                json.loads(p.read_text(encoding="utf-8")), format_checker=jsonschema.FormatChecker()
            )
            for p in schema_dir.glob("*.json")
        }

    def validate(self, event: dict[str, Any]) -> None:
        self._validators[event["eventType"]].validate(event)


def _emit(
    sink: Sink, events: Iterable[dict[str, Any]], validator: SchemaValidator | None, stats: Counter[str]
) -> int:
    n = 0
    for event in events:
        if validator:
            validator.validate(event)
        sink.send(ev.TOPICS[event["eventType"]], event["key"], event)
        stats[event["eventType"]] += 1
        n += 1
    return n


def publish_refdata(
    lake: Lake, sink: Sink, *, validate: bool = False, isins: set[str] | None = None
) -> Counter[str]:
    stats: Counter[str] = Counter()
    _emit(sink, ev.security_events(lake, isins), SchemaValidator() if validate else None, stats)
    sink.flush()
    return stats


def publish_days(lake: Lake, sink: Sink, start: date, end: date, *, validate: bool = False) -> Counter[str]:
    stats: Counter[str] = Counter()
    validator = SchemaValidator() if validate else None
    tbill = lake.read_silver("tbill_curve")
    for day in ev.business_days(lake, start, end):
        counts: dict[str, int] = {}
        curve = ev.curve_event(lake, day, tbill if not tbill.is_empty() else None)
        if curve:
            _emit(sink, [curve], validator, stats)
        counts["reported"] = _emit(sink, ev.reported_events(lake, day), validator, stats)
        counts["settlements"] = _emit(sink, ev.settlement_events(lake, day), validator, stats)
        _emit(sink, [ev.eod_event(day, counts, curve is not None)], validator, stats)
        sink.flush()
        log.info("publish.day", day=str(day), **counts)
    return stats
