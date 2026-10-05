# 0003. Deterministic event ids and idempotent consumers

Status: Accepted

## Context

The daily ingestion job republishes the last seven days to pick up late files and corrections. Kafka delivers at least once, and services restart mid-batch. Without deduplication, a repeated settlement leg would be stored twice and settlement totals would double.

## Decision

Derive `eventId` as the SHA-256 of event type, key, business date and canonical payload. The production timestamp is excluded. Each consumer stores processed ids in a `processed_event` table in the same transaction as the state change, and skips ids it has already processed. Settlement legs use the event id as their primary key.

## Consequences

- Republishing any range is safe and is the standard recovery procedure.
- A corrected record from the source has a different payload, so it produces a new id and is processed as a new event. Tables that keep one row per business key (for example a reported trade per ISIN and date) update that row.
- The `processed_event` table grows with every event and needs periodic pruning of ids older than the republish window in long-running deployments.
