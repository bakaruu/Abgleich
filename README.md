# Abgleich

Bank reconciliation for European companies: matches invoices against bank statements from
Switzerland (ISO 20022 camt.053/054, QR-bill) and Spain (Norma 43). It auto-confirms only what is
provably correct and explains every other proposal.

> Work in progress. Phase F0 (foundations).

## Why it is built this way

- **Hexagonal architecture.** The domain knows nothing about Spring, PostgreSQL, XML or file formats.
  Every input (web, REST, SFTP, bank API) and output (database, events) is an adapter.
- **A catalogue of known failure modes.** Every bug the design protects against has an ID (B01–B45)
  and a test named after it, for example `B02_equality_ignores_scale`.
- **The database is the last line of defence.** Duplicate imports, unsigned amounts and double
  confirmations are rejected by constraints, not only by application code.

## Modules

| Module | Responsibility |
|--------|----------------|
| `domain` | Pure Java model: `Money`, references, IBAN, invariants, matching rules |
| `application` | Use cases and ports |
| `adapters/out-postgres` | Persistence and Flyway migrations |
| `bootstrap` | Spring Boot wiring and configuration |
| `architecture-tests` | ArchUnit rules that keep the architecture honest |

## Run locally

Requirements: JDK 21 and Docker.

```bash
./gradlew build            # compile + all tests (uses Testcontainers)
docker compose up -d       # local PostgreSQL: abgleich-postgres-local-database
./gradlew :bootstrap:bootRun
```

Health check: <http://localhost:8080/actuator/health>

## Decisions

See [`docs/adr`](docs/adr).
