# 0009. dbt with DuckDB locally and Snowflake as the shared target

Status: Accepted

## Context

Analysts need marts that combine lake data with service outputs. The lake is Parquet on local or object storage. A shared warehouse is needed for teams, but local development and CI should not depend on it.

## Decision

Model the marts in dbt. Read silver Parquet through a `silver()` macro that resolves to `read_parquet` on DuckDB and to staged tables on Snowflake. Use DuckDB for local runs and CI, and provide a Snowflake profile for the shared environment. Keep SQL within the subset both engines support.

## Consequences

- `dbt build` runs locally in under a minute with no credentials.
- The read API and the dashboard snapshot are built from the same marts as the warehouse.
- Loading the lake into Snowflake stages is outside this repository.
