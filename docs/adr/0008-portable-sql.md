# 0008. SQL that runs on PostgreSQL and H2

Status: Accepted

## Context

Services run on PostgreSQL 16 in Compose and Kubernetes. Developers and tests need a database without Docker. Vendor-specific SQL, such as `on conflict` upserts, differs between engines.

## Decision

Write migrations and queries in a subset that runs on PostgreSQL and on H2 in PostgreSQL mode. Express upserts as an update followed by an insert when no row was updated. Avoid identifiers that are reserved in either engine. Where H2 plans a query poorly (large common table expressions with joins), run two grouped queries and merge them in Java.

## Consequences

- The same Flyway migrations run in tests, local runs and production.
- Some queries are less concise than PostgreSQL-only versions.
- Differences that H2 does not show are caught only when running against PostgreSQL, in Compose or Kubernetes.
