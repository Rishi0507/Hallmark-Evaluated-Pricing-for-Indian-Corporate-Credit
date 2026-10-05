# Runbook

## Prerequisites

| Tool | Version |
|---|---|
| Python | 3.12 |
| Java | 21 (the Maven wrapper downloads Maven 3.9) |
| Node | 22 |
| Docker | Optional, for the streaming stack |

```bash
python -m venv .venv
.venv/bin/pip install -e "ingest[kafka,dev]" -e "insights-api[dev]" dbt-duckdb
```

On Windows, use `.venv\Scripts\pip`.

## Configuration

Ingestion reads environment variables with the prefix `HALLMARK_`:

| Variable | Default | Meaning |
|---|---|---|
| `HALLMARK_DATA_DIR` | `data` | Root of the lake |
| `HALLMARK_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `HALLMARK_KAFKA_TOPIC_PREFIX` | `hallmark` | Topic prefix |
| `HALLMARK_NSE_MIN_INTERVAL_S`, `HALLMARK_FBIL_MIN_INTERVAL_S`, `HALLMARK_NSDL_MIN_INTERVAL_S`, `HALLMARK_CCIL_MIN_INTERVAL_S` | 0.35, 0.5, 0.3, 1.0 | Minimum seconds between requests per host |
| `HALLMARK_SCHEMA_DIR` | `contracts/schemas` | Schemas used by `publish --validate` |

The services read:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8081 to 8084 | HTTP port |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | H2 file database under `data/h2` | JDBC connection |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka brokers |
| `KAFKA_CONCURRENCY` | 1 | Listener threads per topic |
| `PRICING_UNIVERSE_DAYS` | 180 | Bonds traded or settled in this many days are marked |
| `PRICING_PERSIST_MARKS_FROM` | none | Do not store marks before this date (for long replays) |
| `PRICING_CURVE_GRACE_SECONDS` | 30 | How long pricing waits for the day's curve before using the previous one |
| `EOD_POLL_MS` | 2000 | Interval for checking end-of-day barriers |

## Path 1: batch replay without a broker

This path reproduces the results in [findings.md](findings.md).

```bash
# Download and normalise
hallmark-ingest acquire --start 2025-01-01 --end 2026-10-14
hallmark-ingest transform
hallmark-ingest acquire-nsdl --since 2025-01-01
hallmark-ingest transform

# Build the services and the replay runner
cd services && ./mvnw -q install -DskipTests && cd ..

# Export events and replay them
hallmark-ingest publish --sink file --out data/exports/replay --start 2026-04-01 --end 2026-10-14
java -jar services/backfill/target/backfill-0.1.0-SNAPSHOT-exec.jar data/exports/replay data/exports/backfill
hallmark-ingest load-outputs

# Warehouse, snapshot, dashboard
cd warehouse && cp profiles.example.yml profiles.yml && dbt build --profiles-dir . && cd ..
python scripts/export_snapshot.py
python scripts/summarise.py 2026-07-01
cd web && npm ci && npm run dev
```

Approximate durations on a laptop: full acquisition of 21 months 2 to 3 hours (paced), NSDL enrichment of 7,000 ISINs about 2 hours, transform 3 minutes, replay of 132 days 2 to 3 minutes, dbt build under 1 minute.

## Path 2: streaming stack

```bash
docker compose -f deploy/compose/docker-compose.yml up --build -d
docker compose -f deploy/compose/docker-compose.yml run --rm ingest publish --start 2026-10-01 --end 2026-10-14
docker compose -f deploy/compose/docker-compose.yml run --rm ingest sink
```

| Endpoint | URL |
|---|---|
| Dashboard | http://localhost:8080 |
| refdata-service | http://localhost:8081/swagger-ui.html |
| tradeflow-service | http://localhost:8082/swagger-ui.html |
| pricing-service | http://localhost:8083/swagger-ui.html |
| recon-service | http://localhost:8084/swagger-ui.html |
| insights-api | http://localhost:8090/docs |

The services can also run from the IDE or with `java -jar` against H2 and a local broker. Each service creates its output topics on start; the ingest topics are created by `kafka-init` in Compose.

## Daily operation

The `ingest-daily` CronJob runs `hallmark-ingest daily --publish-to kafka` at 19:00 IST on weekdays. It:

1. Downloads NSE and FBIL files for the last 7 days (missing files from the last 5 days are retried).
2. Transforms new partitions.
3. Refreshes NSDL data for ISINs active in the last 30 days, and takes a CCIL snapshot.
4. Publishes reference data and the last 7 days of market events. Already processed events are ignored by the services because their ids are unchanged.

The `data-refresh` workflow in GitHub Actions runs `hallmark-ingest daily` without publishing, prints any data quality failures and uploads the silver layer as a build artifact.

## GitHub Actions settings

| Repository variable | Effect when `true` |
|---|---|
| `PAGES_ENABLED` | `cd` publishes the dashboard to GitHub Pages (Pages source must be set to GitHub Actions) |
| `DATA_REFRESH_SCHEDULE` | `data-refresh` runs on its weekday schedule; otherwise it runs only when started manually |
| `KUBE_DEPLOY` | `cd` applies the Kubernetes manifests with the `KUBECONFIG` secret after pushing images |

Images are always pushed to GHCR on a push to `main`.

## Monitoring

| Signal | Where | Action |
|---|---|---|
| Consumer lag per group | Kafka, `kafka-consumer-groups.sh --describe --all-groups` | Lag that grows across days means a service is not keeping up or is failing |
| Messages on `*.dlq` topics | Kafka | Inspect the exception headers; fix and replay from the source topic |
| `eod_control` rows still `PENDING` | tradeflow, pricing and recon schemas | The day is waiting for events; compare stored counts with the control event |
| `curve_fit` rows with source `POOLED` for most buckets | pricing schema | Too few observations for the day; marks fall back to matrix pricing |
| Quarantine files | `data/silver/_dq/quarantine` | Review failing rows; a run that fails the 25% threshold usually means a source format change |
| HTTP health | `/actuator/health/liveness`, `/actuator/health/readiness` | Used by Kubernetes probes |
| Metrics | `/actuator/prometheus` | Listener latency, JVM, HTTP |

## Common procedures

**A source file was published late.** Run `hallmark-ingest acquire --start <date> --end <date>` and `transform`. If the date is older than the five-day recheck window, delete the date's entry from `data/bronze/_manifest/<dataset>.jsonl` or remove the missing marker, then rerun. Publish the date again; only new events are processed.

**A source changed its file format.** The transform fails for that dataset with a data quality error or a parser error, and earlier partitions are unaffected. Update the parser and its fixture test in `ingest/tests`, then run `transform --rebuild`.

**Reprocess a date range in the services.** Republish the range with `hallmark-ingest publish`. Events with unchanged ids are skipped. To recompute a pricing date with the stored inputs, call `POST /api/v1/runs/{date}/requeue` on pricing-service; the date is marked pending and processed again.

**A message is on a DLQ topic.** Read it with `kafka-console-consumer.sh --topic <topic>.dlq --property print.headers=true`. The `kafka_dlt-exception-message` header gives the cause. After the fix, republish the original events.

**Resolve a reconciliation break.** `PATCH /api/v1/breaks/{breakId}` on recon-service with `{"status": "RESOLVED", "comment": "...", "user": "..."}`. The comment is required.

## Tests

```bash
cd ingest && pytest --cov=hallmark_ingest && ruff check . && cd ..
cd services && ./mvnw verify && cd ..
cd insights-api && pytest && cd ..
cd web && npm run lint && npm test && npm run build && cd ..
cd warehouse && dbt build --profiles-dir . && cd ..
cd analytics/spark && pytest && cd ../..
```

`./mvnw verify` enforces JaCoCo line coverage per module: 90% for `bond-math`, 70% for the services and 60% for `platform-commons`. Current coverage is 93% to 98% for the services and `bond-math`. The embedded Kafka integration tests take about one minute per service.
