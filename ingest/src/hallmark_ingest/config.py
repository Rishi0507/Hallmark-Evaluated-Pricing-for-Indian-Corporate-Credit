from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/129.0 Safari/537.36"
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="HALLMARK_", env_file=".env", extra="ignore")

    data_dir: Path = Path("data")
    user_agent: str = DEFAULT_USER_AGENT
    http_timeout_s: float = 30.0

    nse_min_interval_s: float = 0.35
    fbil_min_interval_s: float = 0.5
    nsdl_min_interval_s: float = 0.3
    ccil_min_interval_s: float = 1.0

    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_topic_prefix: str = "hallmark"
    kafka_client_id: str = "hallmark-ingest"

    @property
    def bronze_dir(self) -> Path:
        return self.data_dir / "bronze"

    @property
    def silver_dir(self) -> Path:
        return self.data_dir / "silver"

    @property
    def exports_dir(self) -> Path:
        return self.data_dir / "exports"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
