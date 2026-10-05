# Decision records

| Number | Decision | Status |
|---|---|---|
| [0001](0001-public-data-only.md) | Use only public, unauthenticated data sources | Accepted |
| [0002](0002-event-driven-services.md) | Event-driven services on Kafka with one schema per service | Accepted |
| [0003](0003-deterministic-event-ids.md) | Deterministic event ids and idempotent consumers | Accepted |
| [0004](0004-end-of-day-barriers.md) | Count-based end-of-day barriers | Accepted |
| [0005](0005-spread-curves-and-hierarchy.md) | Z-spread curves by rating bucket with a marking hierarchy | Accepted |
| [0006](0006-carried-marks-in-yield-space.md) | Carry recent trades in yield space with partial rate pass-through | Accepted |
| [0007](0007-batch-replay-runner.md) | Batch replay runner that reuses the service domain logic | Accepted |
| [0008](0008-portable-sql.md) | SQL that runs on PostgreSQL and H2 | Accepted |
| [0009](0009-warehouse-targets.md) | dbt with DuckDB locally and Snowflake as the shared target | Accepted |

Each record states the context, the decision and its consequences. A record is not edited after acceptance; a later record supersedes it.
