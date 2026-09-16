# ADR 0003: Money as BigDecimal with fixed scale and currency

- Status: accepted
- Date: 2026-09-16

## Context

Amounts come from XML ("1250.0"), fixed-width text with implied decimals ("00000000181500") and
user input. `double` loses precision (B01); `BigDecimal.equals` treats "1250.0" and "1250.00" as
different (B02); CHF and EUR must never be mixed (B06).

## Decision

A `Money` record holds a `BigDecimal` normalized to scale 2 plus a `Currency` with two minor units.
Values with more decimals are rejected, never rounded. Arithmetic across currencies throws.
Splitting uses `allocate(n)`, which never loses a cent (B03).

## Consequences

- Equality and hashing are safe for collections and matching.
- Currencies with other minor units (JPY) are out of scope until needed.
