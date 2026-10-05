# 0002. Event-driven services on Kafka with one schema per service

Status: Accepted

## Context

Reference data, trade flow, pricing and reconciliation have different owners in a typical institution, change at different rates and have different consumers. Pricing depends on reference data and trade observations; reconciliation depends on raw trades and settlements but not on pricing.

## Decision

Split the domain into four Spring Boot services that communicate only through Kafka topics with versioned JSON contracts. Each service owns one database schema, migrated by Flyway, and no service reads another service's tables. The golden security record is published on a compacted topic so that consumers keep a local copy.

## Consequences

- A service can be rebuilt from its input topics without touching the others.
- Events across topics are not ordered, which requires explicit end-of-day barriers ([0004](0004-end-of-day-barriers.md)).
- Contracts become the interface and need their own tests ([events.md](../events.md)).
- Local development needs a broker. The embedded broker in integration tests and the batch replay runner ([0007](0007-batch-replay-runner.md)) reduce this cost.
