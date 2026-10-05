# Data sources

All inputs are published without authentication. Each host has its own minimum interval between requests, every request is retried up to five times with exponential backoff on HTTP 429 and 5xx and on transport errors, and every downloaded file is kept unchanged in the bronze layer with a manifest entry. A file already present in bronze is not downloaded again. A daily file that was not published is recorded as missing; missing files within the last five days of a run's date range are requested again on the next run, to pick up late publication.

| Host | Minimum interval |
|---|---|
| `nsearchives.nseindia.com` | 0.35 s |
| `www.fbil.org.in` | 0.5 s |
| `indiabondinfo.nsdl.com` | 0.3 s, three workers |
| `www.ccilindia.com` | 1.0 s |

## NSE

| Dataset | URL pattern | Grain | Used for |
|---|---|---|---|
| `cbm_bhavcopy` | `archives/debt/cbm/cbm_trdYYYYMMDD.csv` | ISIN, trade date | Reported trades: last and weighted average price and yield, total face value traded |
| `cbm_settle_list_man`, `cbm_settle_list_non_man`, `cbm_settle_unlist_man`, `cbm_settle_unlist_non_man` | `content/cbm/cbm_{list\|unlist}_{man\|non_man}_DDMMYYYY.csv` | ISIN, trade date, settlement date | Settled legs by listing status and counterparty class |
| `cbm_fail` | `content/cbm/cbm_fail_DDMMYYYY.csv` | ISIN, trade date | Settlement fails |
| `cp_settle`, `cd_settle` | `content/cd/{CP\|CD}_SettlementOrderReport_DDMMYYYY.csv` | ISIN, trade date | Commercial paper and certificate of deposit settlements |
| `ntrp_master` | `content/debt/Corporate_bond_report_DD-Mon-YYYY.csv` | ISIN | NSE security master: issuer, coupon, maturity, face value |

All NSE files are under `https://nsearchives.nseindia.com/`. Observations from the data:

- The bhavcopy column "Total Trade Value" is face value in lakh, not consideration. On 13 October 2026 it matched settled nominal for 97% of ISINs.
- Settlement files are published per settlement date and contain trades from earlier trade dates.
- Security descriptions encode coupon, kind, maturity and face value (for example `SR 1 NCD`, `FV 10000`). Series numbers are not coupons; the parser handles both two- and four-digit years.

## FBIL

| Dataset | Endpoint | Grain | Used for |
|---|---|---|---|
| `gsec_valuation` | `wasdm/gsec/downloadPublished?date=YYYY-MM-DD` (xlsx) | Date | G-sec prices and yields by ISIN, and the par curve by tenor |
| `tbill_curve` | `wasdm/tbill/fetchfiltered?fromDate&toDate&authenticated=false` | Date range | T-bill yields |
| `fx_reference` | `wasdm/refrates/fetchfiltered?fromDate&toDate&authenticated=false` | Date range | Reference rates for USD, EUR, GBP, JPY, AED, IDR and RUB |

All FBIL endpoints are under `https://www.fbil.org.in/`. The valuation file returns HTTP 500 on non-publication days. These dates are recorded as missing in the same way as other daily files. The par curve in the valuation file is the risk-free input for pricing.

## NSDL India Bond Info

Per-ISIN JSON endpoints under `https://indiabondinfo.nsdl.com/bds-service/v1/public/`:

| Endpoint | Used for |
|---|---|
| `isins?isin=` | Instrument: issuer, CIN, industry, ownership, coupon basis, face value, allotment and maturity dates, call and put dates |
| `bdsinfo/credit-ratings?isin=` | Rating history per agency with action, watch and outlook |
| `bdsinfo/coupondetail?isin=` | Coupon schedule |
| `bdsinfo/redemptions?isin=` | Redemption schedule |

NSDL is queried for the ISIN universe that traded or settled in the period (6,954 ISINs, 23,110 requests). It is the only public source of full cash-flow schedules for Indian corporate bonds, and its schedules have defects that the parser flags rather than repairs:

| Warning | Meaning | Count (6,161 schedules) | Severity |
|---|---|---|---|
| `NO_PRINCIPAL` | No principal redemption in the schedule | 601 | Information |
| `MISSING_AMOUNT` | A coupon or redemption row has no amount | 269 | Critical |
| `COUPON_AMOUNT_INCONSISTENT` | Coupon amounts do not match the stated rate and face | 57 | Critical |
| `ZERO_INTEREST_AMOUNTS` | All coupon amounts are zero on a coupon-bearing bond | 14 | Critical |

A critical warning makes the security ineligible for pricing. NSDL rating dates earlier than 1950 (10 records) are treated as missing.

## CCIL

Snapshots of the NDS-OM market watch and the FPI limit utilisation page, fetched by POSTing an empty body to the Liferay resource URLs of the public pages. These are point-in-time snapshots without history and are stored for context; they are not used in pricing.

## Coverage

| Dataset | Period | Rows |
|---|---|---|
| Reported trades | 1 Jan 2025 to 14 Oct 2026 | 193,754 |
| Settlement legs | 1 Jan 2025 to 14 Oct 2026 | 265,299 |
| G-sec par curve | 432 publication days | 86,400 tenor points |
| NSDL ratings | Full history for 5,131 ISINs, 10 agencies | 48,703 |
| NSDL cash flows | 6,161 schedules | 83,917 |

## Terms of use

The repository contains small slices of these files as test fixtures and aggregated outputs. It does not redistribute full files. Anyone running the pipeline downloads the data directly from the publishers and is responsible for complying with their terms.
