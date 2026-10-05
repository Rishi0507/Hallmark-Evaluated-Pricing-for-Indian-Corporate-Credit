# Architecture

## System context

```mermaid
flowchart TB
    subgraph Public[Public data publishers]
        NSE[NSE: bhavcopy, NTRP security master, clearing settlement and fail files]
        FBIL[FBIL: G-sec valuation and par curve, T-bill curve, FX reference rates]
        NSDL[NSDL India Bond Info: issuer, ratings, cash-flow schedules, call and put terms]
        CCIL[CCIL: NDS-OM market watch, FPI limits]
    end
    H[Hallmark]
    U1[Valuation and risk users]
    U2[Operations and control users]
    U3[Product and distribution users]
    NSE --> H
    FBIL --> H
    NSDL --> H
    CCIL --> H
    H -->|marks, curves, backtest| U1
    H -->|lifecycle, breaks, data quality| U2
    H -->|retail flow, rating actions| U3
```

## Containers

```mermaid
flowchart LR
    subgraph Batch[Scheduled]
        ING[ingest<br/>Python CLI]
        SPK[Spark jobs]
        DBT[dbt build]
    end
    subgraph Stream[Event driven]
        REF[refdata-service :8081]
        TF[tradeflow-service :8082]
        PR[pricing-service :8083]
        RC[recon-service :8084]
    end
    subgraph Serve[Serving]
        API[insights-api :8090]
        WEB[web: nginx and React]
    end
    LAKE[(Lake: bronze and silver Parquet)]
    WH[(Warehouse: DuckDB or Snowflake)]
    K{{Kafka}}
    PG[(PostgreSQL: one schema per service)]

    ING --> LAKE
    ING --> K
    K --> REF & TF & PR & RC
    REF & TF & PR & RC --> PG
    REF & TF & PR & RC --> K
    K -->|sink| LAKE
    LAKE --> DBT --> WH --> API
    LAKE --> SPK
    WEB --> API
    WEB --> REF & TF & PR & RC
```

| Component | Responsibility | State |
|---|---|---|
| `ingest` | Download public files with per-host pacing, keep raw bytes and a manifest, parse to typed Parquet, run data quality expectations, publish contract events, land service outputs back in the lake | Lake |
| `refdata-service` | Merge NSDL and NSE records into a golden record per ISIN with field lineage, source conflicts, issuer key, sector group, point-in-time ratings and pricing eligibility | `refdata` schema |
| `tradeflow-service` | Follow each ISIN and trade date from report to settlement, classify the lifecycle state, build validated price observations | `tradeflow` schema |
| `pricing-service` | Bootstrap the risk-free curve, fit credit curves, mark every eligible bond, score yesterday's model against today's trades | `pricing` schema |
| `recon-service` | Reconcile reported against settled trades, NSE yields against the depository schedule, and NSDL against NSE terms; manage breaks | `recon` schema |
| `insights-api` | Read API over the dbt marts | Warehouse, read only |
| `web` | Dashboard built from a JSON snapshot of the marts; its nginx also routes `/api/<service>/` to each service API | None |

## Event flow for one business date

```mermaid
sequenceDiagram
    autonumber
    participant I as ingest
    participant K as Kafka
    participant RD as refdata-service
    participant TF as tradeflow-service
    participant PR as pricing-service
    participant RC as recon-service

    I->>K: SecurityMasterRecord (NSDL, NSE)
    K->>RD: merge to golden record
    RD->>K: GoldenSecurity (compacted topic)
    K->>TF: cache security reference
    K->>PR: cache security view
    I->>K: MarketCurve (FBIL par curve)
    I->>K: ReportedTrade x N
    I->>K: SettlementRecord x M
    I->>K: EndOfDay {reported: N, settlements: M}
    K->>TF: store reports and settlement legs
    K->>RC: store reports and settlement legs
    Note over TF,RC: Barrier: process the day only when N reports and M legs are stored
    TF->>TF: lifecycle states for trade dates D-10..D
    TF->>K: TradeObservation x P, TradeLifecycle changes
    TF->>K: EndOfDay {observations: P}
    K->>PR: observations and control event
    Note over PR: Barrier: P observations stored and curve for D present (or grace elapsed)
    PR->>PR: score D-1 model on D trades, fit model for D, mark universe
    PR->>K: Mark x U, EndOfDay {marks: U}
    RC->>RC: settlement, yield and reference data controls
    RC->>K: ReconBreak changes
```

Kafka orders messages within a partition, not across topics. A day's control event can therefore arrive before its last trade event. Every consumer that acts at end of day stores the control event, waits until the announced number of events has been stored, and then processes days strictly in date order.

## Delivery guarantees

- Producers use idempotent, `acks=all` publishing.
- Every event carries a deterministic `eventId` (SHA-256 of type, key, business date and canonical payload). Consumers record processed ids in their own schema inside the same transaction as the state change, so replays and redeliveries are absorbed.
- Downstream events are published after the database transaction commits.
- Listener errors are retried with exponential backoff. Malformed events, argument errors and SQL errors that cannot succeed on retry go straight to `<topic>.dlq` with the exception in headers. Every failed attempt is logged with topic, partition and offset.

## Storage

Each service owns one PostgreSQL schema, migrated by Flyway. The SQL is written to run unchanged on PostgreSQL 16 and on H2 in PostgreSQL mode, which the services use for local runs and tests. Upserts are expressed as update-then-insert rather than vendor-specific syntax, and aggregations that H2 plans poorly are done as two grouped queries merged in memory.

The lake has two layers:

| Layer | Format | Rules |
|---|---|---|
| Bronze | Files exactly as downloaded, partitioned by business date or snapshot date | Append-only manifest per source with URL, HTTP status, byte count and SHA-256 |
| Silver | Typed Parquet (zstd), one partition per date | Written only after the dataset's expectations pass; failing rows are quarantined |

The warehouse reads silver through a dbt macro that resolves to `read_parquet` on DuckDB and to staged tables on Snowflake.

## Batch replay

`services/backfill` runs the four services' domain logic in one process over exported event files, in the same order the streaming path uses. It reuses the services' classes as libraries (the Boot executable jars are published with an `exec` classifier). The six-month history in [findings.md](findings.md) was produced this way; the streaming path is exercised end to end on real days by each service's integration test against an embedded broker.

## Deployment

```mermaid
flowchart TB
    subgraph GH[GitHub]
        CI[ci.yml: lint, tests, coverage gates, contract check, image builds]
        CD[cd.yml: push images to GHCR, publish dashboard to Pages, apply manifests]
        DR[data-refresh.yml: weekday acquisition and transform]
    end
    subgraph K8S[Kubernetes namespace hallmark]
        D1[Deployments: four services, insights-api, web]
        CJ[CronJob: ingest daily 19:00 IST]
        HPA[HPA: pricing-service on CPU]
        PVC[(PVC: lake)]
    end
    GHCR[(GHCR images)]
    PAGES[GitHub Pages: static dashboard]
    CI --> CD
    CD --> GHCR --> D1
    CD --> PAGES
    CJ --> PVC
    D1 --> PVC
```

Service containers run as a non-root user with read-only root filesystems. Services expose Spring Boot liveness and readiness probes and Prometheus metrics at `/actuator/prometheus`.
