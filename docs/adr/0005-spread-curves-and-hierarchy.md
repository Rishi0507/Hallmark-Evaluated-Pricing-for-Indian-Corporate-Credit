# 0005. Z-spread curves by rating bucket with a marking hierarchy

Status: Accepted

## Context

About 420 corporate bond ISINs trade on a typical day, out of about 26,000 outstanding. Most positions must be marked from other bonds' trades. Trades are sparse, concentrated in AAA public sector issuers and noisy at the low end of the rating scale.

## Decision

Model credit spreads as z-spreads over the bootstrapped FBIL curve. Fit a level and a log-tenor slope per rating bucket with Huber-weighted least squares on recent observations, fall back to a pooled fit across buckets when a bucket has little data, enforce monotonicity across buckets, and add sector and issuer offsets shrunk towards zero.

Mark each bond with the first applicable method: own institutional trade, own retail trade blended with the model, recent own trade carried forward, issuer curve, rating matrix. Record the method, the evidence and a confidence score on every mark.

## Consequences

- Every mark can be traced to specific trades.
- Robust weights and shrinkage keep a single trade from moving a curve or an issuer far.
- The confidence score is checked against the daily backtest rather than assumed.
- Bonds without own or issuer trades rely on bucket curves and have high errors (median 55 bp). This is reported rather than hidden.
- Option-adjusted spreads for callable and puttable bonds are not modelled.
