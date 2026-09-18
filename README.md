# Abgleich

[![CI](https://github.com/bakaruu/Abgleich/actions/workflows/ci.yml/badge.svg)](https://github.com/bakaruu/Abgleich/actions/workflows/ci.yml)

**Abgleich reconciles invoices against bank statements from Switzerland and Spain: ISO 20022 XML and fixed-width
Norma 43. It auto-confirms only what is provably correct, explains every other proposal, and ships with a
catalogue of 45 known failure modes, each one pinned by a test.**

![Loading the example, the review queue, invoices and the summary](docs/images/abgleich-tour.gif)

*Synthetic data only. [Screenshots](docs/images) · [Bug catalogue](docs/bug-catalogue.md) ·
[Architecture decisions](docs/adr) · [Deploying the demo](deploy/README.md)*

## What it does

- **Imports** camt.053 (versions 001.04 and 001.08), camt.054 notifications, Norma 43 and a documented CSV format.
  The format is detected from the content, balances must add up, and every payment is stored once even when files
  overlap or arrive twice.
- **Matches** payments with six rules, from an exact QR or creditor reference (R1) to one payment for several
  invoices (R6). Only R1 confirms on its own; everything else waits in a review queue with the rule, its
  confidence and a readable reason.
- **Reviews** proposals in the browser (Thymeleaf + htmx) or through a JSON API with optimistic locking: a stale
  or repeated decision never changes anything twice.
- **Integrates** like a real back office: statements arrive by upload, REST, an SFTP drop or a scheduled download
  from the bank API, and all four give the same result. Invoices arrive from the ERP as Kafka events;
  `InvoicePaid` goes back through a transactional outbox, so no event is lost or invented.
- **Reports** what was settled automatically, what waits for people, the unidentified money per currency and how
  often reviewers agree with each rule, on a Summary screen, through the API and as Prometheus metrics with a
  Grafana dashboard.
- **Explains itself afterwards**: every request, dropped file, message and scheduled run carries one correlation id
  through its JSON logs and back in the `X-Correlation-Id` response header, and every imported file logs one line
  with its import id, counts and outcome — ids and numbers only, never anything the bank file says about people
  ([ADR 0012](docs/adr/0012-one-correlation-id-per-unit-of-work.md)).

## Measured, not claimed

`./gradlew evaluateMatching` runs the matcher over 300 labelled Swiss and Spanish payments, including traps: the
same amount from another payer, look-alike company names, numbers that are not invoice numbers, debits carrying
references and real ties. The build fails on any wrong automatic confirmation.

| Rule | Signal | Confidence | Precision |
|------|--------|-----------:|----------:|
| R1 | QR or creditor reference and amount exact — the only rule that confirms alone | 1.00 | 80/80 |
| R2 | Same reference, different amount: partial, overpayment, bank charges, closed invoice | 0.90 | 50/50 |
| R3 | Reference one character away, exact amount | 0.85 | 20/20 |
| R4 | Invoice number in the remittance text, exact amount | 0.80 | 35/35 |
| R5 | Similar payer name close to the due date | 0.70 | 40/40 |
| R6 | Exact sum of two to five invoices of one debtor | 0.75 | 15/15 |

Wrong automatic confirmations: **0**. Proposals for payments without an invoice: **0**. The dataset is written
together with the rules, so these figures are an upper bound; real bank files will find cases it lacks, and each
one becomes a new labelled case ([ADR 0006](docs/adr/0006-matching-rules-and-review.md)).

### And the tests are measured too

A green suite only proves the tests ran. `./gradlew :domain:pitest :application:pitest` changes the code on purpose
— inverting conditions, moving thresholds one step, returning constants — and reports how much of that the tests
notice.

| | Domain | Use cases |
|---|---:|---:|
| Mutations | 500 | 209 |
| Killed by the tests | **92 %** | **86 %** |
| Test strength (of the mutations the tests reach) | 94 % | 95 % |
| The build fails below | 90 % | 82 % |

The domain started at 86 % and the use cases at 64 %, and the survivors were worth reading. Every threshold in the
matching rules was tested comfortably on one side of it, so moving a boundary by one cent, one day or one character
broke nothing the tests could see; the search behind rule R6 could have ignored its own limits; loading the example
twice, cancelling an invoice that lost a race and a summary reporting a negative count were never asserted at all.
Eight test classes later, those are covered and the score is a number the build defends. CI runs both on every push.

What survives now is mostly what cannot be killed: mutations that change the code without changing its behaviour.
Chasing those would mean writing tests that assert implementation details, which is how a suite becomes a burden.

### And so is the speed

`./gradlew benchmark -Pbenchmark.transactions=5000` generates a statement far larger than a real day's file,
imports it and prints where the time goes. Measured on a Ryzen 9 9950X3D with PostgreSQL in Docker; what matters is
the shape, not the absolute numbers.

| Statement | Parse only | Match in memory | Import and reconcile, end to end |
|-----------|-----------:|----------------:|---------------------------------:|
| 1 000 transactions | 40 ms · 25 000/s | 1.1 s · 906/s | 6.3 s · 158/s |
| 2 000 transactions | 68 ms · 29 000/s | 2.7 s · 737/s | 17.0 s · 118/s |
| 5 000 transactions | 154 ms · 32 000/s | 13.8 s · 361/s | 70.3 s · 71/s |

Parsing is not the problem: camt.053 streams through StAX at about 25 MB/s and never holds the file in memory.
The cost is matching, and it grows with the ledger, because every payment is compared against every open invoice of
the account. A real day's file (a few hundred lines against a few hundred open invoices) is the first row and takes
seconds; the table is deliberately worse than reality to show the curve.

Measuring it paid for itself immediately: the profile showed rule R5 comparing company names — the most expensive
thing the matcher does — *before* checking the cheap conditions that would discard the invoice anyway, and comparing
the same two names once per invoice instead of once per pair. Fixing both made matching **7× faster** (2 000
transactions: 19.4 s → 2.7 s) and the whole import **1.6× faster**, with the same 2 771 decisions, the same
precision over the labelled dataset and the same golden file.

The remaining growth is by design, not by accident: the next step for a real ledger is narrowing candidates in SQL
(by reference and amount) instead of reading every open invoice, and it is not done because nothing here needs it
yet.

## Edge cases handled

Every failure mode the design protects against has an ID and at least one test named after it. The build checks
that [the catalogue](docs/bug-catalogue.md) and the tests agree. A few of them:

| ID | What goes wrong | Protection |
|----|-----------------|------------|
| [B11](docs/bug-catalogue.md) | A truncated file is imported with wrong balances | Opening + credits − debits must equal closing, or nothing is stored |
| [B16](docs/bug-catalogue.md) | Two identical legitimate transfers are merged into one | Deduplication key with an ordinal for identical lines |
| [B21](docs/bug-catalogue.md) | Two simultaneous uploads of the same file both store it | Unique constraints decide, not a prior query |
| [B23](docs/bug-catalogue.md) | The event is published but the payment rolls back, or the reverse | Transactional outbox and relay |
| [B27](docs/bug-catalogue.md) | A wrong match is confirmed without a person | Only R1 confirms; precision measured over 300 cases |
| [B32](docs/bug-catalogue.md) | `<script>` in a transfer's remittance text runs in the reviewer's browser | Escaped output, strict CSP, browser test fails on console errors |
| [B43](docs/bug-catalogue.md) | A visitor uploads a real statement to the public demo | Banner, example buttons, every table purged nightly |

## Architecture

Everything points inwards. The adapters on the left start the work, the core decides, and the adapters on the
right are reached only through ports the core defines.

```mermaid
flowchart LR
    subgraph in["Driving adapters"]
        direction TB
        web["Web UI · htmx"] ~~~ rest["REST API"] ~~~ sftp["SFTP drop"] ~~~ kin["Kafka consumer"] ~~~ sched["Scheduler"]
    end

    subgraph core["The hexagon · plain Java"]
        direction TB
        app["<b>application</b><br/>use cases and ports"] --> dom["<b>domain</b><br/>Money · Invoice · Matcher"]
    end

    subgraph out["Driven adapters"]
        direction TB
        parse["Parsers · camt · Norma 43 · CSV"] ~~~ pg[("PostgreSQL")] ~~~ kout["Outbox relay → Kafka"] ~~~ bank["Bank API client"]
    end

    in ==>|"call use cases"| core
    core ==>|"call ports"| out
```

The domain knows nothing about Spring, PostgreSQL, Kafka, XML or HTML, adapters never depend on each other, and
ArchUnit fails the build when either rule is broken. Swapping PostgreSQL for a map in a test, or adding a fifth way
to receive a statement, changes nothing in the middle column.

### What happens to a statement

```mermaid
flowchart TD
    file["A bank file arrives<br/>upload · REST · SFTP · bank API"] --> detect["Detect the format<br/>from the first bytes, not the file name"]
    detect --> valid["Check the balances<br/>opening + credits − debits = closing"]
    valid --> store["Store the transactions<br/>a duplicate file is refused by the database"]
    store --> match["Match each payment<br/>rules R1 to R6"]
    match -->|"R1 and a single candidate"| settled["The invoice is settled<br/>and the allocation recorded"]
    match -->|"anything else"| review["Review queue<br/>with the rule, its confidence and a reason"]
    review -->|"a person confirms"| settled
    settled --> event["InvoicePaid leaves through<br/>the outbox to Kafka"]
```

Each step refuses bad input before the next one runs, and the database is the last line of defence: duplicate
imports, unsigned amounts, double confirmations and stale writes are rejected by constraints and version checks,
not only by code.

| Module | Responsibility |
|--------|----------------|
| `domain` | Pure Java model: `Money`, references, IBAN, statements, invoices, events, matching rules |
| `application` | Use cases and ports grouped by capability (statement, invoice, reconciliation, events, reporting, example); parser contract suite in test fixtures |
| `adapters/in-web` | Upload, review, invoice and summary screens (Thymeleaf + htmx) |
| `adapters/in-rest` | JSON API with Problem Details, `If-Match` and `Idempotency-Key` |
| `adapters/in-kafka` | `InvoiceCreated` consumer with a dead letter topic |
| `adapters/in-sftp` | SFTP drop: `.done` markers, `processed/` and `error/` folders |
| `adapters/in-scheduler` | Bank download, outbox relay and retention, demo reset; one instance at a time |
| `adapters/out-camt` | camt.053 and camt.054 with streaming StAX |
| `adapters/out-norma43` | Norma 43 fixed-width files |
| `adapters/out-csv` | Abgleich CSV |
| `adapters/out-postgres` | Spring JDBC and Flyway: constraints, outbox, inbox, summary queries |
| `adapters/out-kafka` | Publishes `InvoicePaid` and `InvoiceReopened` |
| `adapters/out-bank-api` | HTTP client of the bank statement API |
| `adapters/out-synthetic` | Deterministic example data and the labelled matching dataset |
| `bootstrap` | Spring Boot wiring, security, metrics, rate limit and end-to-end tests |
| `architecture-tests` | ArchUnit rules and the bug catalogue check |
| `mock-bank` | A stand-in bank statement API for tests and local runs |
| `smoke-tests` | Playwright browser journey and the README screenshots |

## Run locally

Requirements: JDK 21 and Docker.

```bash
./gradlew build              # compile and all tests (Testcontainers starts PostgreSQL and Kafka)
./gradlew evaluateMatching   # precision per matching rule over 300 labelled payments
./gradlew :domain:pitest :application:pitest   # mutation testing: do the tests notice a change?
./gradlew benchmark         # parse, match and import a large statement; prints where the time goes
docker compose up -d         # PostgreSQL, Kafka and an SFTP drop, all named abgleich-*-local-*
./gradlew :bootstrap:bootRun
```

Open <http://localhost:8080>, press **Load both**, then open **Review** and **Summary**. Metrics are at
<http://localhost:8080/actuator/prometheus>.

The SFTP drop and the bank API are off by default. To try them locally:

```bash
./gradlew :mock-bank:run     # bank API on port 8090, serving the example statements

ABGLEICH_SFTP_ENABLED=true ABGLEICH_SFTP_PASSWORD=sftp-local-only ABGLEICH_SFTP_ALLOW_UNKNOWN_KEYS=true \
  ABGLEICH_BANK_API_ENABLED=true ABGLEICH_BANK_API_TOKEN=mock-bank-local-only ./gradlew :bootstrap:bootRun
```

Upload a statement to `inbox/` on `sftp://bank@localhost:2222`, followed by an empty `<name>.done` file. Paid
invoices appear as events on the Kafka topic `abgleich.invoice-events`.

The whole public demo (Caddy, Prometheus, Grafana, nightly reset, rate limit) runs locally too: see
[deploy/README.md](deploy/README.md).

## Scope

Not included, on purpose: real bank connections (EBICS, PSD2), outgoing payments (pain.001, Norma 34), full
accounting or several companies, currency conversion, and user accounts. Decisions are recorded with the channel
that made them, not with a signed-in person; the public demo is open by design and resets every night.

## License

MIT — see [LICENSE](LICENSE).
