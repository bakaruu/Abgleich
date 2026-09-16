# Abgleich

Portfolio project: European bank reconciliation engine built with hexagonal architecture.
Matches invoices (CHF, EUR) against bank statements: ISO 20022 camt.053/054 (Switzerland, SEPA),
Swiss QR-bill references and Spanish Norma 43.

Full plan (scope, domain model, ports, 45-bug catalogue, phases):
https://claude.ai/artifact/PwSWua5FPB5nCV3cB2c8Sv — read it before starting a new phase.

## Working with the user

- Talk to the user in Spanish. Code, commits, README and ADRs are in English.
- Docker containers, volumes and networks always get descriptive names
  (e.g. `abgleich-postgres-local-database`), never cryptic ones.
- Never run git commands (commit, push, remote...). Tell the user what to run; they do it manually.

## Current phase

F0 foundations (16–22 Sep 2026) — done so far: Gradle multi-module skeleton, value objects
(`Money`, `QrReference`, `CreditorReference`, `Iban`, `Direction`), ArchUnit rules, Flyway V1 schema
with constraint tests, Spring Boot bootstrap, local compose, CI, ADRs 0001–0004.
Next: F1 import end-to-end (camt.053 + Norma 43 parsers, idempotent import, R1 matching, upload UI).

Phases: F0 → 22 Sep, F1 → 13 Oct, F2 → 3 Nov, F3 → 17 Nov, F4 → 1 Dec 2026.

## Stack

Java 21 (toolchain), Spring Boot 4.1.1, Gradle 9.7.1 (wrapper, Kotlin DSL, version catalog in
`gradle/libs.versions.toml`, conventions in `build-logic`), PostgreSQL 17, Flyway, Testcontainers,
JUnit 5, AssertJ, jqwik, ArchUnit. Planned: Thymeleaf + htmx UI, Kafka, SFTP.

## Commands

```bash
./gradlew build                       # everything, needs Docker running
./gradlew :domain:test                # fast, no Docker
docker compose up -d                  # local PostgreSQL
./gradlew :bootstrap:bootRun
```

## Non-negotiable rules

1. No money outside `Money`; no `double`/`float` in the domain (B01–B03, B06).
2. References, IBANs, invoice numbers are normalized, validated value objects (B04, B05).
3. Every movement has an explicit `Direction` (B10); every import validates balances (B11).
4. Parsers stream, disable DTD/external entities, set charset explicitly (B08, B14, B19).
5. Idempotency is enforced by database constraints, not by a prior `if` (B21).
6. Mutable aggregates carry a `version` (B22).
7. Only rule R1 auto-confirms; ties go to manual review (B27, B28).
8. Time comes from an injected `Clock` (B38).
9. `domain` and `application` never import Spring, JPA, Jackson; adapters never depend on each
   other (B36, B40). Matching rules are domain logic, not adapters.
10. Adapters translate technical exceptions into domain exceptions (B39).
11. Text from bank files is untrusted: always escaped, never `th:utext` (B32).
12. No full IBANs or names in logs (`Iban.toString()` is masked); only synthetic fixtures (B41).
13. Every bug ID touched by a feature has a test named with that ID before the feature is done.

## Layout

- `domain/` pure model · `application/` use cases + ports · `adapters/<in|out>-<tech>/`
- `bootstrap/` Spring Boot wiring · `architecture-tests/` ArchUnit · `docs/adr/` decisions
- Bank file fixtures go under `**/fixtures/**` and are byte-exact (see `.gitattributes`).
