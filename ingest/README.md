# hallmark-ingest

Python package that downloads public market data, builds the bronze and silver lake, runs data quality expectations and publishes contract events.

## Modules

| Module | Responsibility |
|---|---|
| `net` | HTTP client with per-host pacing and retries |
| `connectors` | One module per publisher (NSE, FBIL, NSDL, CCIL): URL patterns and fetch logic |
| `lake` | Bronze file layout, manifest, missing-file markers, silver Parquet writes |
| `parsers` | Typed parsing of each file format, including NSE security descriptions and NSDL schedules |
| `quality` | Expectation suites, quarantine and the 25% failure threshold |
| `transform` | Bronze to silver for every dataset |
| `universe` | ISINs active in a period, used to scope NSDL requests |
| `events` | Contract events with deterministic ids |
| `publish` | Kafka and file sinks, optional schema validation |
| `sink` | Kafka to silver for service outputs |
| `outputs` | Loads batch replay outputs into silver |
| `cli` | `hallmark-ingest` command line |

## Commands

| Command | Purpose |
|---|---|
| `acquire --start --end` | NSE and FBIL files for a date range |
| `acquire-nsdl [--since] [--limit]` | NSDL instrument, rating and schedule data for active ISINs |
| `acquire-ccil` | CCIL snapshots |
| `transform [--rebuild]` | Build silver partitions from bronze |
| `publish --start --end [--sink kafka\|file] [--out] [--validate]` | Publish events |
| `daily [--day] [--publish-to kafka\|file]` | Scheduled job: acquire, transform, enrich, publish |
| `sink` | Consume service outputs into silver |
| `load-outputs [--source]` | Load batch replay CSV outputs into silver |

## Tests

```bash
pip install -e ".[kafka,dev]"
pytest --cov=hallmark_ingest
ruff check . && ruff format --check .
```

Parser and pipeline tests run against slices of real files in `tests/fixtures`. HTTP is replaced by `httpx.MockTransport` serving those files, so the tests do not touch the network.
