# ADR 0006: Matching rules, ties and human review

- Status: accepted
- Date: 2026-09-16

## Context

Phase F2 adds rules R2–R6 to the exact rule R1. A wrong automatic confirmation is worse than no match
(B27), ties must never be settled by the order a query returns (B28), and two reviewers or a double
click must not decide a payment twice (B33, B34).

## Decision

**Rules and confidence.** Every rule only proposes candidates; one ranking step decides.

| Rule | Signal | Confidence |
|------|--------|-----------:|
| R1 | QRR or SCOR reference and outstanding amount exact | 1.00 |
| R2 | Same reference, different amount: partial, overpayment, bank charges (B07), closed invoice (B31) | 0.90 |
| R3 | Reference one character away, exact amount | 0.85 |
| R4 | Invoice number in the remittance text, exact amount | 0.80 |
| R5 | Similar payer name, exact or partial amount, due date within 45 days | 0.70 |
| R6 | Exact sum of 2–5 open invoices of one debtor, bounded search (B29) | 0.75 |

Thresholds live in `MatchingPolicy`. Candidates are ranked by confidence, due date, invoice number.
When the runner-up is **less than** 0.05 below the best one, all close candidates go to review and none
is chosen. With the default confidences rules differ by at least 0.05, so in practice only candidates
of the same rule tie. Only R1 with a single candidate and no close competitor confirms automatically.

**Name similarity** is Jaro-Winkler compared word by word in both directions, taking the weakest pair,
after removing accents, punctuation and legal forms. Plain Jaro-Winkler on the whole name scored
"PINTURAS SOL SA" and "PINTURAS LUNA SA" at 0.92, above the threshold. Scores are `BigDecimal` (B01).

**Names inside remittance text.** Norma 43 has no payer field; the ordering party is part of the
concept. When a payment has no payer name, R5 accepts the debtor's whole name as consecutive words of
the text, and only with the exact outstanding amount, so other payments of the same company are not
proposed as partial payments.

**Review.** Allocations proposed together share a group and are confirmed or rejected together. A
decision carries the payment version the reviewer saw: a different version refuses it (B34, HTTP 412
in the API with `If-Match`). Confirming an already confirmed group succeeds without effect (B33); the
database refuses a racing second write and the re-read reports it as already done. Confirming one
proposal rejects the payment's other proposals. Rejected invoice sets are never proposed again for
that payment. Confirmed allocations are only reversed, with a note, when the bank reverses the payment
(B10); nothing is deleted.

**Evaluation.** `./gradlew evaluateMatching` runs the matcher over 300 labelled Swiss and Spanish
payments, including traps: same amount from another payer, look-alike company names, numbers that are
not invoice numbers, debits carrying references and real ties. The build fails if any automatic
confirmation is wrong, if R1 misses an exact reference, or if a rule's precision drops below 95 %.

## Consequences

- Every non-trivial match costs a human decision; the review queue is the product, not a fallback.
- The labelled dataset is written together with the rules, so its 100 % results are an upper bound.
  Real bank files will find cases it does not contain; each one becomes a new labelled case and test.
- A payer who pays several invoices without naming them and without a name the bank reports stays
  unmatched: R6 needs either the invoice numbers or a similar payer name.
