# ADR 0004: Idempotency enforced by the database

- Status: accepted
- Date: 2026-09-16

## Context

The same statement can arrive twice: HTTP retries, SFTP reprocessing, a user uploading again, camt.053
and camt.054 covering the same entries. A "check then insert" in code lets two concurrent requests
both pass the check (B21).

## Decision

Uniqueness is a database constraint: file hash and camt `MsgId` per account for imports, a
deduplication key per account for transactions (B12, B16), invoice number for invoices (B24) and a
partial unique index for active allocations (B33). Code translates the violation into a domain result.

## Consequences

- Duplicates are impossible even under concurrency.
- Norma 43 has no unique bank reference, so its deduplication key must be derived carefully to keep
  legitimate identical movements (B16).
