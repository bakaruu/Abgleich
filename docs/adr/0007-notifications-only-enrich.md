# ADR 0007: camt.054 notifications only enrich stored transactions

- Status: accepted
- Date: 2026-09-16

## Context

Banks send camt.054 notifications during the day and the camt.053 statement at night. Both describe
the same entries. A notification has no balances, so it cannot be validated like a statement (B11), and
storing its entries as transactions would count the same money twice once the statement arrives (B12).

## Decision

A notification never creates transactions. Its entries are located by the same deduplication key a
statement produces (`AcctSvcrRef`, or the derived key) and only fill columns that are still empty:
remittance text, payer name, reference, end-to-end id, charges. Filled values are never overwritten.
Entries not stored yet are reported as unknown. After enriching, the account is reconciled again,
because a new remittance text or payer name can make a waiting payment matchable.

## Consequences

- Balance validation stays mandatory for everything that creates transactions.
- A notification uploaded before the day's statement adds nothing; uploading it again afterwards does.
- An entry the statement stored without transaction details but notified as a batch of several
  transactions has no row per transaction to enrich; those transactions are counted as unknown.
