# ADR 0008: Transactional outbox for published events, inbox table for received ones

- Status: accepted
- Date: 2026-09-17

## Context

Phase F3 connects Abgleich to an ERP through Kafka in both directions.

- **Out:** when a payment settles an invoice, the ERP must hear `InvoicePaid`. Writing to PostgreSQL and
  to Kafka are two systems with no shared transaction. Publish first and roll back, and the ERP books a
  payment that does not exist; commit first and crash, and the ERP never hears about it (B23).
- **In:** the ERP sends `InvoiceCreated`. Kafka delivers at least once, so the same event can arrive
  twice, also at the same moment on a rebalance (B24). REST clients retrying after a timeout have the
  same problem.

## Decision

**Outbox.** The repositories that store a confirmation, a review decision or a reversal also insert the
events it causes into `outbox_event`, inside the same transaction. The application decides which events
exist (`InvoiceEvent.between(before, after)`: only the step into or out of paid/overpaid); the database
decides that they exist exactly when the change committed.

- Typed columns, not a JSON payload: check constraints refuse an `INVOICE_PAID` event for an open invoice,
  and the wire format stays in the Kafka adapter.
- A relay (`PublishEventsService`, scheduled every 2 s under a ShedLock lock) publishes in insertion order
  and marks an event only after Kafka acknowledged it with `acks=all`. It stops at the first failure, so a
  later event never overtakes an earlier one, and keeps a failure count and reason on the row.
- Delivery is **at least once**: a crash between acknowledgement and marking publishes the event again.
  Every event carries a stable `eventId` (payload and header); consumers deduplicate by it. The record key
  is the invoice id, so events of one invoice stay ordered within their partition.
- Amounts travel as decimal strings, never JSON numbers (B01).

**Inbox.** `processed_message (channel, message_id)` is the primary key. One transaction inserts the
invoice with `on conflict (invoice_number) do nothing`, then the message id. If another delivery committed
the same id first, the transaction rolls back, including the invoice it just inserted, and the receipt of
the first processing is returned. Concurrent deliveries wait for each other on the unique indexes; there is
no "was it processed?" query that both could pass (rule 5). The same table backs the REST
`Idempotency-Key` header: a retry gets 200 with the same invoice id, a key reused for another invoice 422.

**Poison messages.** Malformed JSON, a missing field or an invalid invoice raise `RejectedMessageException`
and go straight to `abgleich.invoices.created.dead-letter`. Other failures, such as the database being down,
are retried with exponential backoff for up to ten minutes before being parked there too.

## Consequences

- A confirmed payment and its event can never disagree; the price is a delay of a few seconds and possible
  duplicates, which the event id makes harmless.
- The outbox grows by one row per settled or reopened invoice. Published rows are not deleted yet; a
  retention job belongs to the operations work of F4, together with the `abgleich_outbox_pending` metric.
- Another event type needs a migration (new `event_type` value and constraint), which is deliberate: every
  published contract change is visible in review.
- `processed_message` keeps message ids forever. An ERP that reuses event ids for different invoices gets
  its reused messages refused and logged instead of silently merged.
