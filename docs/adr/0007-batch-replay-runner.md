# 0007. Batch replay runner that reuses the service domain logic

Status: Accepted

## Context

Measuring the model needs months of history replayed day by day. Running four services and a broker for 132 days on a laptop took hours and was sensitive to memory limits. A separate batch implementation of the logic would drift from the services.

## Decision

Keep the domain logic of each service in plain classes without Spring or Kafka dependencies, and publish the service jars as libraries next to the executable jars (the Boot jars use the `exec` classifier). Add a `backfill` module that reads exported event files, runs the four services' logic in one JVM in the same order as the streaming path, and writes the outputs as CSV for loading into the lake.

## Consequences

- The six-month replay runs in two to three minutes and produced the results in [findings.md](../findings.md).
- The streaming path and the batch path share all pricing, lifecycle and reconciliation rules. The streaming path is covered by the embedded Kafka integration tests on real days.
- Service orchestration (barriers, persistence) is not exercised by the batch path.
