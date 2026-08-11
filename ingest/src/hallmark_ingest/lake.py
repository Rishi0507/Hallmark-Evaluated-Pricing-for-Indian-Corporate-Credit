from __future__ import annotations

import hashlib
import json
import os
import threading
from collections.abc import Iterator
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path

import polars as pl

from hallmark_ingest.net import FetchResult


@dataclass(frozen=True)
class BronzeKey:
    source: str
    dataset: str
    partition: str
    filename: str

    @staticmethod
    def for_date(source: str, dataset: str, day: date, filename: str) -> BronzeKey:
        return BronzeKey(source, dataset, f"dt={day.isoformat()}", filename)

    @staticmethod
    def for_snapshot(source: str, dataset: str, day: date, filename: str) -> BronzeKey:
        return BronzeKey(source, dataset, f"snap={day.isoformat()}", filename)

    @property
    def partition_value(self) -> str:
        return self.partition.split("=", 1)[1]


@dataclass(frozen=True)
class ManifestEntry:
    source: str
    dataset: str
    partition: str
    filename: str
    url: str
    status: int
    bytes: int
    sha256: str | None
    fetched_at: str


class Lake:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.bronze_root = root / "bronze"
        self.silver_root = root / "silver"
        self._manifest_lock = threading.Lock()
        self._missing: dict[str, set[tuple[str, str, str]]] = {}

    def bronze_path(self, key: BronzeKey) -> Path:
        return self.bronze_root / key.source / key.dataset / key.partition / key.filename

    def has(self, key: BronzeKey) -> bool:
        return self.bronze_path(key).is_file()

    def known_missing(self, key: BronzeKey) -> bool:
        if key.source not in self._missing:
            self._missing[key.source] = self._load_missing(key.source)
        return (key.dataset, key.partition, key.filename) in self._missing[key.source]

    def write_bronze(self, key: BronzeKey, result: FetchResult) -> Path:
        path = self.bronze_path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".part")
        tmp.write_bytes(result.content)
        os.replace(tmp, path)
        self._append_manifest(
            key, result, sha256=hashlib.sha256(result.content).hexdigest(), size=len(result.content)
        )
        return path

    def record_missing(self, key: BronzeKey, result: FetchResult) -> None:
        self._append_manifest(key, result, sha256=None, size=len(result.content))
        self._missing.setdefault(key.source, set()).add((key.dataset, key.partition, key.filename))

    def iter_bronze(self, source: str, dataset: str) -> Iterator[tuple[str, Path]]:
        base = self.bronze_root / source / dataset
        if not base.exists():
            return
        for part_dir in sorted(p for p in base.iterdir() if p.is_dir() and "=" in p.name):
            for file in sorted(part_dir.iterdir()):
                if file.is_file() and not file.name.endswith(".part"):
                    yield part_dir.name.split("=", 1)[1], file

    def manifest(self, source: str) -> list[ManifestEntry]:
        path = self._manifest_path(source)
        if not path.exists():
            return []
        with path.open(encoding="utf-8") as fh:
            return [ManifestEntry(**json.loads(line)) for line in fh if line.strip()]

    def _manifest_path(self, source: str) -> Path:
        return self.bronze_root / "_manifest" / f"{source}.jsonl"

    def _append_manifest(self, key: BronzeKey, result: FetchResult, *, sha256: str | None, size: int) -> None:
        entry = ManifestEntry(
            source=key.source,
            dataset=key.dataset,
            partition=key.partition,
            filename=key.filename,
            url=result.url,
            status=result.status,
            bytes=size,
            sha256=sha256,
            fetched_at=result.fetched_at.isoformat(),
        )
        path = self._manifest_path(key.source)
        with self._manifest_lock:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(asdict(entry)) + "\n")

    def _load_missing(self, source: str) -> set[tuple[str, str, str]]:
        missing: set[tuple[str, str, str]] = set()
        present: set[tuple[str, str, str]] = set()
        for entry in self.manifest(source):
            ident = (entry.dataset, entry.partition, entry.filename)
            if entry.status == 200 and entry.sha256:
                present.add(ident)
            else:
                missing.add(ident)
        return missing - present

    def silver_path(self, dataset: str, partition: str) -> Path:
        return self.silver_root / dataset / partition / "part-0.parquet"

    def write_silver(self, dataset: str, partition: str, frame: pl.DataFrame) -> Path:
        path = self.silver_path(dataset, partition)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".parquet.part")
        frame.write_parquet(tmp, compression="zstd", statistics=True)
        os.replace(tmp, path)
        return path

    def silver_partitions(self, dataset: str) -> list[str]:
        base = self.silver_root / dataset
        if not base.exists():
            return []
        return sorted(p.name for p in base.iterdir() if p.is_dir() and "=" in p.name)

    def silver_glob(self, dataset: str) -> str:
        return (self.silver_root / dataset / "*" / "*.parquet").as_posix()

    def read_silver(self, dataset: str) -> pl.DataFrame:
        if not self.silver_partitions(dataset):
            return pl.DataFrame()
        return pl.read_parquet(self.silver_glob(dataset), hive_partitioning=False)
