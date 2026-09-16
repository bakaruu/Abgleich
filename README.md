# Abgleich

Bank reconciliation for European companies: matches invoices against bank statements from
Switzerland (ISO 20022 camt.053/054, QR-bill) and Spain (Norma 43). It auto-confirms only what is
provably correct, explains every other proposal and lets a person decide.

> Work in progress. Phases F0 (foundations), F1 (import end to end), F2 (matching and review) and F3
> (integrations) are done.

## What it does

- **Imports** camt.053 (versions 001.04 and 001.08), camt.054 notifications, Norma 43 and a documented
  CSV format. The format is detected from the content, balances must add up, and every payment is
  stored once even when files overlap or arrive twice.
- **Matches** payments with six rules, from an exact QR or creditor reference (R1) to one payment for
  several invoices (R6). Only R1 confirms on its own; everything else waits in a review queue with the
  rule, its confidence and a readable reason.
- **Reviews** proposals in the browser (Thymeleaf + htmx) or through a JSON API with optimistic
  locking: a stale or repeated decision never changes anything twice.
- **Integrates** like a real back office: statements arrive by upload, REST, an SFTP drop or a scheduled
  download from the bank API, and all four give the same result. Invoices arrive from the ERP as Kafka
  events; `InvoicePaid` goes back through a transactional outbox, so no event is lost or invented.

## Why it is built this way

- **Hexagonal architecture.** The domain knows nothing about Spring, PostgreSQL, XML or file formats.
  Every input (web, REST, SFTP, Kafka, scheduler) and output (database, file formats, Kafka, bank API) is
  an adapter.
- **A catalogue of known failure modes.** Every bug the design protects against has an ID (B01–B45)
  and a test named after it, for example `B02_equality_ignores_scale` or `B34_stale_decision_is_refused`.
- **The database is the last line of defence.** Duplicate imports, unsigned amounts, double
  confirmations and stale writes are rejected by constraints and version checks, not only by code.
- **At least once, exactly one effect.** Events leave through an outbox written in the same transaction as
  the payment; received messages are recorded in an inbox table, so a redelivered event changes nothing.
- **Measured matching.** `./gradlew evaluateMatching` runs the matcher over 300 labelled payments and
  fails the build on any wrong automatic confirmation.

## Modules

| Module | Responsibility |
|--------|----------------|
| `domain` | Pure Java model: `Money`, references, IBAN, statements, invoices, matching rules |
| `application` | Use cases and ports; parser contract suite in test fixtures |
| `adapters/in-web` | Upload, review and invoice screens (Thymeleaf + htmx) |
| `adapters/in-rest` | JSON API with Problem Details and `Idempotency-Key` |
| `adapters/in-kafka` | `InvoiceCreated` consumer with a dead letter topic |
| `adapters/in-sftp` | SFTP drop: `.done` markers, `processed/` and `error/` folders |
| `adapters/in-scheduler` | Scheduled bank download and outbox relay, one instance at a time (ShedLock) |
| `adapters/out-camt` | camt.053 and camt.054 with streaming StAX |
| `adapters/out-norma43` | Norma 43 fixed-width files |
| `adapters/out-csv` | Abgleich CSV |
| `adapters/out-postgres` | Persistence with Spring JDBC and Flyway migrations, outbox and inbox tables |
| `adapters/out-kafka` | Publishes `InvoicePaid` and `InvoiceReopened` |
| `adapters/out-bank-api` | HTTP client of the bank statement API |
| `adapters/out-synthetic` | Deterministic example data and the labelled matching dataset |
| `bootstrap` | Spring Boot wiring, security and end-to-end tests |
| `architecture-tests` | ArchUnit rules that keep the architecture honest |
| `mock-bank` | A stand-in bank statement API for tests and local runs (not part of the application) |

## Run locally

Requirements: JDK 21 and Docker.

```bash
./gradlew build              # compile + all tests (uses Testcontainers)
./gradlew evaluateMatching   # precision and recall per matching rule
docker compose up -d         # PostgreSQL, Kafka and an SFTP drop, all named abgleich-*-local-*
./gradlew :bootstrap:bootRun
```

Open <http://localhost:8080> and press **Load example**, then open **Review**.

The SFTP drop and the bank API are off by default. To try them locally:

```bash
./gradlew :mock-bank:run     # bank API on port 8090, serving the example statements

ABGLEICH_SFTP_ENABLED=true ABGLEICH_SFTP_PASSWORD=sftp-local-only ABGLEICH_SFTP_ALLOW_UNKNOWN_KEYS=true \
  ABGLEICH_BANK_API_ENABLED=true ABGLEICH_BANK_API_TOKEN=mock-bank-local-only ./gradlew :bootstrap:bootRun
```

Upload a statement to `inbox/` on `sftp://bank@localhost:2222`, followed by an empty `<name>.done` file.
Paid invoices appear as events on the Kafka topic `abgleich.invoice-events`.

## Decisions

See [`docs/adr`](docs/adr).
