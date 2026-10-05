# Data quality

Data quality is enforced at three points: when a raw file becomes a silver partition, when an event is published, and when the services and the warehouse consume the data.

## Silver expectations

Each dataset has a suite of expectations evaluated on every partition. Results for every expectation, including passes, are appended to `data/silver/_dq/results.jsonl` with the row count, failure count and a sample of failing ISINs.

- A **blocking** expectation removes failing rows from the partition and writes them to `data/silver/_dq/quarantine/<dataset>/<partition>.parquet`.
- A **non-blocking** expectation records the failure and keeps the row.
- If more than 25% of a partition is quarantined, the partition is not written and the run fails, because a failure on that scale indicates a format change rather than bad rows.

### Results for January 2025 to October 2026

| Dataset | Expectation | Blocking | Rows | Failed |
|---|---|---|---|---|
| trades_reported | `isin_format` | yes | 193,754 | 0 |
| trades_reported | `unique_isin_per_day` | yes | 193,754 | 0 |
| trades_reported | `wap_present` | no | 193,754 | 0 |
| trades_reported | `wap_range_0_1000` | no | 193,754 | 25 |
| trades_reported | `way_range_minus5_60` | no | 193,754 | 17 |
| trades_reported | `value_positive` | no | 193,754 | 847 |
| settlements | `isin_format` | yes | 265,302 | 0 |
| settlements | `trade_date_present` | yes | 265,302 | 3 |
| settlements | `trade_not_after_settlement` | yes | 265,302 | 3 |
| settlements | `settlement_within_30d` | no | 265,302 | 28 |
| settlements | `nominal_positive` | no | 265,302 | 0 |
| settlements | `wap_range_0_1000` | no | 265,302 | 2 |
| settlements | `way_range_minus5_60` | no | 265,302 | 22 |
| settlements | `face_value_parsed` | no | 265,302 | 9,097 |
| gsec_prices | `isin_format`, `unique_isin`, `ytm_range_0_20` | mixed | 51,747 | 0 |
| par_curve | `unique_tenor`, `yield_range_0_20` | mixed | 86,400 | 0 |
| ntrp_master | `isin_format`, `unique_isin` | yes | 79,864 | 0 |
| nsdl_cashflows | `due_date_present` | yes | 83,917 | 0 |
| nsdl_cashflows | `amount_positive` | no | 83,917 | 11 |
| nsdl_ratings | `agency_present` | no | 48,703 | 0 |
| nsdl_ratings | `symbol_mapped` | no | 48,703 | 11 |

Notes:

- The three settlement rows without a trade date are the only rows quarantined in the period.
- `value_positive` failures are bhavcopy rows with a total trade value of zero. They carry prices and are kept, but they do not count towards reported face value.
- `settlement_within_30d` failures include the 19 legs settled on 7 February 2025 against August 2024 trade dates.
- `face_value_parsed` failures are bonds whose NSE description does not state a face value. Their settlement legs have denomination class `UNKNOWN` in the distribution marts.

## Event contracts

With `--validate`, the publisher checks every event against its JSON Schema in `contracts/schemas` before sending it, and an event that fails validation stops the run with the path of the failing field. CI runs the contract tests on every change. The services check the same contracts in their tests against the real examples in `contracts/examples` and the replay days in `contracts/replay`.

## Service level checks

| Check | Where | Effect |
|---|---|---|
| Recomputed yield within 25 bp of reported | tradeflow-service | Observation flagged `YIELD_MISMATCH` and not used for pricing |
| No reference data, or security not eligible | tradeflow-service | Observation flagged `NO_REFDATA` or `NOT_PRICEABLE` |
| Critical NSDL schedule warning | refdata-service | Security ineligible with reason `SCHEDULE_DEFECT` |
| Source conflicts on coupon, maturity, face value | refdata-service | Listed on the golden record; coupon and maturity also raise recon breaks |
| Spread outside 3,000 bp or tenor below 0.1 years | pricing-service | Observation excluded from curve fits |

## Warehouse tests

dbt runs 36 models and tests on every build: `not_null` and `unique` on keys, `accepted_values` on rating bucket and denomination class, and a custom `positive` test on traded counts, settled face and backtest counts. CI parses the project on every change. The full build with tests needs the lake and runs locally.
