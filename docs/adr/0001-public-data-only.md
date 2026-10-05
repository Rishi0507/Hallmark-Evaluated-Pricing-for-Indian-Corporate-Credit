# 0001. Use only public, unauthenticated data sources

Status: Accepted

## Context

Evaluated pricing is normally built on vendor feeds, dealer quotes and internal trade data. None of these can be shared in a public repository, and simulated data would make every result unverifiable.

Indian regulators require exchange reporting of corporate bond trades and publication of settlement data, and FBIL and NSDL publish benchmark curves and depository reference data. Together these cover trades, settlement, reference data and the risk-free curve.

## Decision

Use only files and APIs that NSE, FBIL, NSDL and CCIL publish without authentication. Do not generate synthetic records anywhere, including tests. Test fixtures are slices of real downloaded files.

Keep every downloaded file unchanged in a bronze layer with a manifest (URL, status, size, SHA-256), pace requests per host and do not download the same file twice.

## Consequences

- Every number in the documentation can be reproduced from the publishers.
- The model has no quotes for untraded bonds and only one weighted average price per ISIN per day. Accuracy for bonds without recent trades is limited by this.
- Source defects (missing NSDL amounts, inconsistent coupons, implausible rating dates) must be detected and handled rather than avoided.
- Public endpoints change without notice. Parsers are tested against fixtures and the transform fails on format changes instead of writing partial data.
