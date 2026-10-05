# Distribution analytics

Individual investors now buy corporate bonds directly. SEBI reduced the minimum face value of privately placed debt securities to ₹10,000, and online bond platforms (OBPPs) sell these bonds to individuals through stock brokers. SEBI modified the OBPP framework again on 14 August 2026. The distribution analytics measure which bonds reach this channel, at what credit quality and yield, and which issuers combine retail flow with negative credit signals.

## Classifications

**Denomination class.** Derived from the face value per unit stated in the NSE security description and the NSDL record.

| Class | Face value per unit |
|---|---|
| `RETAIL` | Up to ₹10,000 |
| `HNI` | Above ₹10,000 and below ₹1 lakh |
| `INSTITUTIONAL` | ₹1 lakh and above |

**Counterparty class.** NSE Clearing publishes separate settlement files for trades between mandatory RFQ participants (banks, insurers, mutual funds and other regulated institutions required to report on the RFQ platform) and trades involving non-mandatory participants (brokers on behalf of clients, OBPPs and others). The file a leg comes from sets its class: `MANDATORY` or `NON_MANDATORY`.

A settled leg of a `RETAIL` denomination bond with a `NON_MANDATORY` counterparty is the closest public proxy for an individual investor purchase. Settlement files do not identify the investor, so the measure is an upper bound on retail participation within those bonds and a lower bound for the market as a whole, because retail investors also buy larger denominations.

**Rating and sector.** Taken from the golden record: the worst current long-term rating across agencies, bucketed, and a sector group (PSU bank, private bank, PSU financial, HFC, NBFC, PSU corporate, corporate) derived from the NSDL industry classification and ownership fields.

## Marts

| Mart | Grain | Measures |
|---|---|---|
| `fct_distribution_monthly` | Month, denomination class, counterparty class, rating bucket, sector group | Settlement legs, ISINs, issuers, settled face (₹ crore), value-weighted yield |
| `fct_retail_watchlist` | Issuer | Retail face and legs in the last 90 days, retail value-weighted yield, worst rating, fails in the last 180 days, downgrades and negative watch or outlook actions in the last 365 days |
| `fct_rating_actions_monthly` | Month, action type | Long-term rating actions and ISINs from NSDL rating history, classified as upgrade, downgrade, withdrawal, new rating, or reaffirmation and other |

All windows are anchored on the latest settlement date in the data, not on the run date, so results are reproducible from the same lake.

## Watchlist signals

An issuer enters the watchlist when it had retail denomination settlements in the last 90 days. It is then ranked on:

| Signal | Source | Window |
|---|---|---|
| Downgrades | NSDL rating history, action contains "downgrade" | 365 days |
| Negative signals | Rating watch negative or outlook negative | 365 days |
| Settlement fails | NSE Clearing fail files | 180 days |
| Retail face and yield | Retail denomination, settled legs | 90 days |

The watchlist does not express a credit view. It lists issuers where individual investors are buying and at least one public signal has moved against the issuer, so that distribution and risk teams can review them first.

## Rating migration

The Spark job `rating_transitions` builds a one-year migration matrix from NSDL rating history:

1. For each instrument and agency, take the latest rating effective on or before the cohort date. Withdrawn ratings are excluded from the starting cohort.
2. Take the latest rating effective on or before the cohort date plus 365 days.
3. Bucket both and count transitions. A rating withdrawn by the end date, or with no rating at the end date, is counted as `WITHDRAWN`.

Ratings are counted per instrument and agency pair, so an issuer with many rated instruments weighs more than one with few. The matrix is used to check whether the rating buckets the pricing model treats as distinct behave differently over a year.

## Issuer liquidity

The Spark job `issuer_liquidity` aggregates settled bond legs by issuer and month: settled face, number of ISINs traded, number of distinct settlement days and the share of face settled in retail denominations. It identifies issuers whose bonds trade often enough for the issuer offset in the pricing model to be estimated from recent trades.
