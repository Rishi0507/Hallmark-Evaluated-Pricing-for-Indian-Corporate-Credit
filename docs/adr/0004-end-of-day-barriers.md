# 0004. Count-based end-of-day barriers

Status: Accepted

## Context

Trade flow, pricing and reconciliation need all of a day's events before they act. Kafka orders messages within a partition only, so an end-of-day control event can be read before the last trade event of that day on another partition or topic. Time-based waits would be either slow or unsafe.

## Decision

The producer of each batch publishes an `EndOfDay` event with the number of events of each type it published for the date. Consumers store the control event, poll until they have stored that many events for the date, and process dates strictly in order. Pricing also waits for the day's curve for a configurable grace period and then uses the previous curve.

## Consequences

- Results do not depend on delivery timing. The embedded Kafka tests and the batch replay produce the same outputs for the same days.
- A lost event blocks the date and every later date. This is visible as a `PENDING` row in `eod_control` and is resolved by republishing.
- Each stage that produces a batch (tradeflow, pricing) publishes its own control event with counts for the next stage.
