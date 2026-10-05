# Contributing

## Branches and pull requests

- `main` is always releasable. Every change reaches it through a pull request.
- Branch names: `feature/<topic>`, `fix/<topic>`, `docs/<topic>`, `chore/<topic>`.
- Keep a pull request to one concern. Changes to an event contract go in their own pull request, together with the producer and every consumer.
- CI must pass: lint, unit and integration tests, coverage gates, contract check and image builds.
- At least one approving review from a code owner is required.

## Commit messages

Conventional Commits, imperative mood, one line of at most 72 characters:

```
feat(pricing): add issuer curve offsets with shrinkage
fix(ingest): parse two-digit years in NSE security descriptions
docs: add reconciliation methodology
```

Scopes: `ingest`, `contracts`, `bond-math`, `platform`, `refdata`, `tradeflow`, `pricing`, `recon`, `backfill`, `warehouse`, `spark`, `api`, `web`, `deploy`, `ci`.

## Review checklist

**Correctness**

- [ ] Units are explicit in names (`Bps`, `Pct`, `Lakh`, `Inr`, `Crore`) and conversions are tested.
- [ ] Date logic states whether days are calendar or business days.
- [ ] Day count, settlement and compounding conventions match the methodology documents.
- [ ] New rules are tested on real data from the fixtures, not on invented values.

**Data and contracts**

- [ ] Schema changes are regenerated with `scripts/gen_schemas.py` and are backward compatible, or use a new topic version.
- [ ] New datasets have data quality expectations, with blocking checks only for rows that cannot be used.
- [ ] No synthetic records anywhere in the repository.

**Services**

- [ ] Consumers are idempotent on `eventId` and update state in one transaction.
- [ ] Events are published after the transaction commits.
- [ ] SQL runs on both PostgreSQL and H2; migrations are additive.
- [ ] Errors that cannot succeed on retry are classified as non-retryable.

**Clean code**

- [ ] Domain logic has no framework dependencies and is covered by unit tests.
- [ ] Names explain intent; no commented-out code.
- [ ] Public behaviour changes are reflected in the documentation.

## Tests

Write the test first for a rule or a bug fix. The coverage gates (JaCoCo per module, `pytest --cov` for ingestion) fail the build when coverage drops below the configured minimum.

See the [runbook](docs/runbook.md#tests) for the commands that CI runs.

## Documentation

Methodology changes update the relevant document in `docs/methodology` in the same pull request. Significant design decisions get a new record in `docs/adr`.
