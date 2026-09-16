# Abgleich

Bank reconciliation for European companies: matches invoices against bank statements from
Switzerland (ISO 20022 camt.053/054, QR-bill) and Spain (Norma 43). It auto-confirms only what is
provably correct, explains every other proposal and lets a person decide.

> Work in progress. Phases F0 (foundations), F1 (import end to end) and F2 (matching and review) are done.

## What it does

- **Imports** camt.053 (versions 001.04 and 001.08), camt.054 notifications, Norma 43 and a documented
  CSV format. The format is detected from the content, balances must add up, and every payment is
  stored once even when files overlap or arrive twice.
- **Matches** payments with six rules, from an exact QR or creditor reference (R1) to one payment for
  several invoices (R6). Only R1 confirms on its own; everything else waits in a review queue with the
  rule, its confidence and a readable reason.
- **Reviews** proposals in the browser (Thymeleaf + htmx) or through a JSON API with optimistic
  locking: a stale or repeated decision never changes anything twice.

## Why it is built this way

- **Hexagonal architecture.** The domain knows nothing about Spring, PostgreSQL, XML or file formats.
  Every input (web, REST) and output (database, file formats) is an adapter.
- **A catalogue of known failure modes.** Every bug the design protects against has an ID (B01–B45)
  and a test named after it, for example `B02_equality_ignores_scale` or `B34_stale_decision_is_refused`.
- **The database is the last line of defence.** Duplicate imports, unsigned amounts, double
  confirmations and stale writes are rejected by constraints and version checks, not only by code.
- **Measured matching.** `./gradlew evaluateMatching` runs the matcher over 300 labelled payments and
  fails the build on any wrong automatic confirmation.

## Modules

| Module | Responsibility |
|--------|----------------|
| `domain` | Pure Java model: `Money`, references, IBAN, statements, invoices, matching rules |
| `application` | Use cases and ports; parser contract suite in test fixtures |
| `adapters/in-web` | Upload, review and invoice screens (Thymeleaf + htmx) |
| `adapters/in-rest` | JSON API with Problem Details |
| `adapters/out-camt` | camt.053 and camt.054 with streaming StAX |
| `adapters/out-norma43` | Norma 43 fixed-width files |
| `adapters/out-csv` | Abgleich CSV |
| `adapters/out-postgres` | Persistence with Spring JDBC and Flyway migrations |
| `adapters/out-synthetic` | Deterministic example data and the labelled matching dataset |
| `bootstrap` | Spring Boot wiring, security and end-to-end tests |
| `architecture-tests` | ArchUnit rules that keep the architecture honest |

## Run locally

Requirements: JDK 21 and Docker.

```bash
./gradlew build              # compile + all tests (uses Testcontainers)
./gradlew evaluateMatching   # precision and recall per matching rule
docker compose up -d         # local PostgreSQL: abgleich-postgres-local-database
./gradlew :bootstrap:bootRun
```

Open <http://localhost:8080> and press **Load example**, then open **Review**.

## Decisions

See [`docs/adr`](docs/adr).
