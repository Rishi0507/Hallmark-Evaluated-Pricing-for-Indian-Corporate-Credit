# Services

Maven multi-module build for the JVM components. Java 21, Spring Boot 4.1.

| Module | Type | Port | Purpose |
|---|---|---|---|
| `bond-math` | Library | | Schedules, accrual, yield, price, z-spread, G-spread, duration, curve bootstrap |
| `platform-commons` | Library | | Event envelope, publisher, idempotency, dead letter handling, error responses; test helpers for contracts and replay days |
| `refdata-service` | Service | 8081 | Golden security records |
| `tradeflow-service` | Service | 8082 | Trade lifecycle and price observations |
| `pricing-service` | Service | 8083 | Curves, marks and backtest |
| `recon-service` | Service | 8084 | Reconciliation controls and break management |
| `backfill` | CLI | | Batch replay of exported events through the service logic |

## Layout of a service

```
src/main/java/io/hallmark/<service>/
    domain/        rules and models, no Spring or Kafka dependencies
    persistence/   JdbcClient repositories
    messaging/     Kafka listeners
    api/           REST controllers
    *Coordinator   end-of-day barriers and scheduling
src/main/resources/db/migration/   Flyway migrations
src/test/java/                     unit tests on real data, embedded Kafka integration test
```

## Build

```bash
./mvnw verify                      # tests and coverage gates
./mvnw -q install -DskipTests      # jars only
java -jar pricing-service/target/pricing-service-0.1.0-SNAPSHOT-exec.jar
```

Executable jars carry the `exec` classifier so that the plain jars can be used as libraries by `backfill`.

## APIs

Each service serves OpenAPI documentation at `/swagger-ui.html`.

| Service | Endpoints under `/api/v1` |
|---|---|
| refdata | `GET /securities` (filters: text, issuer, sector, rating bucket, eligibility; paged), `GET /securities/{isin}`, `GET /ratings/actions`, `GET /refdata/stats`, `GET /refdata/conflicts` |
| tradeflow | `GET /trades`, `GET /trades/{isin}`, `GET /lifecycle/summary`, `GET /settlement/channels`, `GET /observations`, `GET /eod` |
| pricing | `GET /marks`, `GET /marks/{isin}`, `GET /marks/{isin}/{date}`, `GET /marks/summary`, `GET /curves/{date}`, `GET /backtest/summary`, `GET /backtest/predictions`, `GET /runs`, `POST /runs/{date}/requeue` |
| recon | `GET /breaks`, `GET /breaks/{breakId}`, `PATCH /breaks/{breakId}`, `GET /breaks/summary`, `GET /breaks/aging`, `GET /recons/runs` |
