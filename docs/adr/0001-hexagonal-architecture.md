# ADR 0001: Hexagonal architecture

- Status: accepted
- Date: 2026-09-16

## Context

Abgleich receives the same kind of data through very different channels (web upload, REST, SFTP,
a bank API) and in very different formats (camt XML, fixed-width Norma 43, CSV). The matching rules
are the valuable part and must be testable without infrastructure.

## Decision

Use ports and adapters. `domain` holds the model and matching rules, `application` holds use cases
and ports, and every technology lives in an adapter. Matching rules are domain logic and are **not**
placed behind a port; only external concerns are.

## Consequences

- Domain tests run in milliseconds without Spring or Docker.
- More classes: persistence entities and mappers are separate from domain objects.
- `@Transactional` cannot be used inside use cases; transactions are applied in `bootstrap`.
