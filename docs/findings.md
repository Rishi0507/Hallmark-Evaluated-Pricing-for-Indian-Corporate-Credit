# Findings

All figures come from public data for 1 January 2025 to 14 October 2026. The service results come from replaying 1 April to 14 October 2026 (132 business days) through the four services' domain logic. They can be reproduced with the commands in the [runbook](runbook.md) and `scripts/summarise.py`.

## Market context

| Measure | Value |
|---|---|
| Bond ISINs traded on NSE per day | 418 on average, range 12 to 701 |
| Outstanding corporate bond ISINs (NSDL, March 2026 quarter) | about 25,900 |
| 10-year G-sec par yield (FBIL) | 6.90% on 1 January 2025, 7.55% on 14 October 2026, range 6.29% to 7.55% |
| USD/INR (FBIL reference rate) | 85.71 on 1 January 2025, record 96.84 on 20 May 2026, 96.77 on 8 October 2026 |

Fewer than 2% of outstanding corporate bonds trade on a given day. A daily valuation process therefore marks most positions from something other than their own trade on that day.

## 1. Bond math and conventions

**G-sec valuation.** With T+1 settlement and 30E/360 accrual, pricing each G-sec from FBIL's published yield reproduces FBIL's published price for all 119 fixed-coupon G-secs on 8 October 2026 within 0.0006 per 100. The bootstrapped curve reprices every par point to 100 within 1e-9 up to 45 years. Beyond that, FBIL's par yields, rounded to two decimals, imply small negative forward rates, and two knots (46.5 and 50 years) are floored.

**NSE corporate bond yields.** For trades from July to October 2026 on bonds with clean NSDL schedules, the yield recomputed from NSE's weighted average price matched NSE's reported yield with a median absolute difference of 0.27 bp; 70.6% were within 2 bp and 93% within 25 bp. T+0 settlement fits marginally better than T+1.

## 2. Valuation accuracy

### Coverage

| Measure | Value |
|---|---|
| Golden security records | 7,107 |
| Eligible for pricing | 3,803 |
| Marks produced | 229,730 over 132 days, 1,740 per day on average, 2,458 distinct ISINs |
| Method mix on 14 October 2026 | ISSUER_CURVE 1,136, CARRIED 689, TRADED_RETAIL 306, TRADED 189, MATRIX 66 |

### Out-of-sample error, July to October 2026

Each institutional trade is predicted from the previous day's model and data. Error is predicted yield minus traded yield, in basis points.

| Method | Trades | Mean abs error | Median abs error | 90th percentile | Mean error | Within 25 bp |
|---|---|---|---|---|---|---|
| CARRIED | 12,640 | 15.8 | 7.4 | 37.1 | +1.3 | 82.9% |
| ISSUER_CURVE | 1,577 | 31.8 | 17.8 | 71.3 | +2.1 | 59.5% |
| MATRIX | 101 | 87.6 | 55.0 | 168.3 | +33.4 | 20.8% |

### Against naive baselines

For the same 12,640 trades of bonds with an institutional trade in the previous 30 days:

| Approach | Mean abs error | Median abs error |
|---|---|---|
| Model (CARRIED) | 15.8 | 7.4 |
| Last traded yield, unchanged | 15.9 | 7.0 |
| Last z-spread on today's curve | 17.2 | 8.8 |

For recently traded bonds, the model matches the last traded yield on mean error and is 0.4 bp worse on median error. Carrying the spread unchanged, which passes the full G-sec move through to the bond, is worse than both. The main value of the model is not in beating the last trade for bonds that trade, but in covering the bonds that do not, with a stated confidence that is borne out by the backtest.

### Confidence calibration

| Confidence | Trades | Median abs error |
|---|---|---|
| 0.8 and above | 9,415 | 6.3 |
| 0.6 to 0.8 | 3,873 | 12.1 |
| 0.4 to 0.6 | 929 | 14.3 |
| Below 0.4 | 101 | 55.0 |

Error increases monotonically as confidence falls, so the score can be used to route marks for review.

### By rating bucket

| Bucket | Trades | Median abs error | Mean abs error |
|---|---|---|---|
| AAA | 4,869 | 6.2 | 11.0 |
| AA+ | 1,090 | 8.7 | 16.4 |
| AA | 1,895 | 9.3 | 17.7 |
| AA- | 1,014 | 9.8 | 22.2 |
| A+ | 1,144 | 8.8 | 20.0 |
| A | 1,109 | 8.7 | 18.2 |
| A- | 1,418 | 10.0 | 20.3 |
| BBB | 1,689 | 12.6 | 30.7 |
| UNRATED | 81 | 28.2 | 84.2 |
| SUB_IG | 9 | 22.3 | 85.9 |

### Rate pass-through

Between consecutive trades of the same bond within 30 days, the change in the bond's yield was regressed on the change in the G-sec par yield at the same tenor:

| Period | Slope | Correlation |
|---|---|---|
| April to June 2026 (used to set the parameter) | 0.531 | 0.185 |
| July to October 2026 (test) | 0.165 | 0.046 |

Corporate bond yields followed only a fraction of short-horizon G-sec moves, and the relationship was weak and unstable. An earlier version of the model that carried spreads with full pass-through was worse than the last traded yield. The pass-through was set to 0.25 from the April to June sample and not changed after.

## 3. Trade flow and settlement

**Settlement lag.** Of 265,299 settlement rows: T+0 64.29%, T+1 26.85%, T+2 1.55%, T+3 5.97%, T+4 1.17%. More than 99.99% settled within 7 calendar days. The exceptions are 19 legs settled on 7 February 2025 with trade dates of 13 August 2024, 178 days earlier.

**Fails.** 3,284 fail rows covering ₹5,592 crore of face value over the period.

**Trade to settlement, corporate bonds, trade dates up to 6 October 2026:**

| Outcome | ISIN-trade dates |
|---|---|
| Matched | 65,525 |
| Partially settled | 1,997 |
| Settlement failed | 876 |
| Not settled | 605 |
| Settled, not reported | 239 |
| Price mismatch | 38 |
| Settled exceeds reported | 20 |

94.6% of bond ISIN-trade dates reconcile. Other instrument classes show structural patterns and are reported separately from the bond match rate:

| Class | Matched | Main break | Count |
|---|---|---|---|
| Certificates of deposit | 10 | Settled, not reported | 7,636 |
| Commercial paper | 1,743 | Settled, not reported | 1,495 |
| G-secs and T-bills | 2,106 | Not settled in NSE Clearing data | 718 |

Most CD trades and a large part of CP trades settle through NSE Clearing but are not in the NSE bhavcopy, because they are reported on other platforms. G-sec trades that do not appear in NSE Clearing data settle through CCIL.

## 4. Reference data

**Yield recomputation.** Of 63,265 reported trades with a fixed coupon NSDL schedule:

| Outcome | Trades |
|---|---|
| Matched within 25 bp | 53,626 |
| Explained: price quoted on original face of an amortising bond | 507 |
| Explained: next-day settlement convention | 54 |
| Break: NSDL schedule defect | 906 |
| Break: NSDL schedule incomplete | 608 |
| Break: unexplained yield mismatch | 7,564 |

84.8% match. Yield to call and yield to put were tested for every mismatch and did not explain any trade in the period. 1,514 breaks point to depository schedule problems rather than to the trade.

**NSDL schedules.** Of 6,161 schedules, 601 have no principal redemption, 269 have rows with missing amounts, 57 have coupon amounts inconsistent with the stated rate and 14 have zero coupon amounts on a coupon-bearing bond. Ten rating records carry dates before 1950.

**Source agreement.** For 1,821 ISINs in both NSDL and the NSE security master, coupons agree for all and maturity dates differ for 173.

**Rating dispersion.** 42 ISINs have current ratings from two or more agencies that differ by two notches or more.

## 5. Distribution

**Settled bond flow, April to October 2026:**

| Counterparty | Denomination | Legs | Face (₹ crore) |
|---|---|---|---|
| Mandatory | Institutional | 20,448 | 667,950 |
| Non-mandatory | Institutional | 31,075 | 194,415 |
| Non-mandatory | Retail (face up to ₹10,000) | 40,542 | 15,302 |

In 2026, retail denomination bonds were 50.4% of non-mandatory bond settlement legs but 5.85% of non-mandatory face value. Small tickets are now the majority of transactions in this channel.

**Credit quality of retail denomination flow, all counterparties, January to October 2026:**

| Rating | Face (₹ crore) | Value-weighted yield | Legs |
|---|---|---|---|
| A | 6,189 | 11.08% | 9,446 |
| BBB | 6,012 | 12.96% | 16,809 |
| A- | 3,719 | 11.95% | 7,509 |
| AA+ | 3,396 | 9.07% | 2,153 |
| A+ | 3,022 | 10.75% | 5,240 |
| AA | 2,836 | 9.71% | 3,723 |
| AA- | 2,487 | 9.72% | 5,702 |
| AAA | 1,028 | 6.41% | 1,852 |
| Unrated | 805 | 17.36% | 108 |

BBB bonds account for the largest number of retail legs, at a value-weighted yield of about 13%. AAA bonds are 2% of retail denomination legs.

**Watchlist.** Issuers with retail flow and negative public signals include a microfinance lender rated A- with six negative watch or outlook actions in the last year, an AA rated NBFC with six settlement fails in 180 days, and a BBB rated lender with three downgrades. The full list is in the dashboard and in `fct_retail_watchlist`.

## 6. Rating migration

One-year migration from 1 October 2025 to 1 October 2026, by instrument and agency pair:

| From | Pairs | Unchanged | Upgraded | Downgraded | Withdrawn |
|---|---|---|---|---|---|
| AAA | 4,584 | 96.8% | | <0.1% | 3.2% |
| AA+ | 961 | 83.8% | 12.9% | 0.0% | 3.3% |
| AA | 914 | 71.3% | 27.6% | 0.0% | 1.1% |
| AA- | 352 | 62.5% | 31.8% | 0.3% | 5.4% |
| A+ | 336 | 79.5% | 14.6% | 1.2% | 4.8% |
| A | 178 | 79.8% | 12.9% | 1.1% | 6.2% |
| A- | 174 | 84.5% | 6.9% | 1.7% | 6.9% |
| BBB | 250 | 78.4% | 8.4% | 6.8% | 6.4% |
| Sub-investment grade | 79 | 83.5% | 6.3% | | 10.1% |

Upgrades outnumbered downgrades in every investment grade bucket below AAA, most strongly in the AA range. BBB is the only bucket with a material downgrade rate: 6.4% moved to sub-investment grade and 0.4% to default within the year.

## Limitations

- The replay covers six and a half months. Parameters were chosen on April to June and evaluated on July to October, which is a single split rather than a rolling evaluation.
- The bhavcopy reports one weighted average price per ISIN per day, so intraday dispersion and trade sizes are not available.
- Settlement files do not identify investors; the retail measures are proxies based on denomination and counterparty class.
- CCIL and RFQ quote data, which would add evidence for untraded bonds, are not used.
