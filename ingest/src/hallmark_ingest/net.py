from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlsplit

import httpx
import structlog
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential_jitter,
)

log = structlog.get_logger(__name__)

RETRYABLE_STATUS = {429, 502, 503, 504}


class TransientHttpError(Exception):
    pass


@dataclass(frozen=True)
class FetchResult:
    url: str
    status: int
    content: bytes
    content_type: str
    fetched_at: datetime

    @property
    def ok(self) -> bool:
        return self.status == 200

    def looks_like_html(self) -> bool:
        head = self.content[:512].lstrip().lower()
        return head.startswith(b"<!doctype html") or head.startswith(b"<html")


@dataclass
class HostPacer:
    min_interval_s: float
    _last: float = 0.0
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            delay = self._last + self.min_interval_s - now
            if delay > 0:
                time.sleep(delay)
            self._last = time.monotonic()


class PoliteClient:
    def __init__(
        self,
        user_agent: str,
        intervals: dict[str, float],
        default_interval_s: float = 0.5,
        timeout_s: float = 30.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._client = httpx.Client(
            headers={
                "User-Agent": user_agent,
                "Accept": "*/*",
                "Accept-Language": "en-US,en;q=0.9",
            },
            timeout=timeout_s,
            follow_redirects=True,
            transport=transport,
        )
        self._default_interval = default_interval_s
        self._pacers: dict[str, HostPacer] = {
            host: HostPacer(interval) for host, interval in intervals.items()
        }
        self._pacers_lock = threading.Lock()

    def _pacer(self, url: str) -> HostPacer:
        host = urlsplit(url).hostname or ""
        with self._pacers_lock:
            if host not in self._pacers:
                self._pacers[host] = HostPacer(self._default_interval)
            return self._pacers[host]

    @retry(
        retry=retry_if_exception_type((TransientHttpError, httpx.TransportError)),
        wait=wait_exponential_jitter(initial=1.0, max=30.0),
        stop=stop_after_attempt(5),
        reraise=True,
    )
    def request(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
        data: dict[str, Any] | str | None = None,
    ) -> FetchResult:
        self._pacer(url).wait()
        response = self._client.request(
            method,
            url,
            params=params,
            headers=headers,
            content=data if isinstance(data, str) else None,
            data=data if isinstance(data, dict) else None,
        )
        if response.status_code in RETRYABLE_STATUS:
            log.warning("http.retryable", url=url, status=response.status_code)
            raise TransientHttpError(f"{response.status_code} for {url}")
        return FetchResult(
            url=str(response.url),
            status=response.status_code,
            content=response.content,
            content_type=response.headers.get("content-type", ""),
            fetched_at=datetime.now(UTC),
        )

    def get(self, url: str, **kwargs: Any) -> FetchResult:
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs: Any) -> FetchResult:
        return self.request("POST", url, **kwargs)

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> PoliteClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
