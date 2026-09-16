# ADR 0005: Spring JDBC instead of JPA for persistence

- Status: accepted
- Date: 2026-09-16

## Context

The plan listed JPA entities with mappers in `out-postgres`. The first persistence use case, importing
a statement, needs:

- `insert ... on conflict (account_iban, dedup_key) do nothing`, so overlapping files skip known
  transactions without a prior query (B12, B16, B21);
- batch inserts of thousands of rows per file (B14);
- the name of the violated constraint, to tell a duplicate import from any other failure (B39).

JPA hides the SQL that carries these guarantees, adds a persistence context that caches thousands of
entities per import, and would still need native queries for `on conflict`.

## Decision

`out-postgres` uses Spring JDBC (`JdbcClient` for queries, `JdbcTemplate` for batches) with explicit
SQL. Each repository method that writes runs in one short `TransactionTemplate` transaction owned by
the adapter; use cases stay free of `@Transactional`. Domain objects are mapped by hand to rows.

## Consequences

- The SQL that enforces idempotency is visible in one place and tested against a real PostgreSQL.
- No lazy loading or dirty checking: writes happen only where the code says so.
- More mapping code by hand; optimistic locking (B22, B34) is written as `where version = ?` and
  checked through the update count.
- Switching to JPA later for read-heavy screens remains possible behind the same ports.
