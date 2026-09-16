-- Phase F3: outbox for published events, inbox for received messages, and locks for scheduled jobs.

-- B23: an event is stored in the same transaction as the change that causes it and published afterwards.
-- Typed columns instead of a JSON payload: the database checks every event like any other row, and the
-- wire format belongs to the Kafka adapter.
create table outbox_event (
    id              uuid          primary key,
    position        bigint        generated always as identity,  -- publishing order
    event_type      varchar(24)   not null,
    invoice_id      uuid          not null references invoice (id),
    invoice_number  varchar(35)   not null,
    creditor_iban   varchar(34)   not null,
    currency        char(3)       not null,
    amount          numeric(19,2) not null,
    paid_amount     numeric(19,2) not null,
    invoice_status  varchar(24)   not null,
    occurred_at     timestamptz   not null,
    published_at    timestamptz,
    attempts        integer       not null default 0,
    last_error      varchar(500),
    constraint uq_outbox_position unique (position),
    constraint ck_outbox_event_type check (event_type in ('INVOICE_PAID', 'INVOICE_REOPENED')),
    constraint ck_outbox_amount_positive check (amount > 0),
    constraint ck_outbox_paid_not_negative check (paid_amount >= 0),
    constraint ck_outbox_attempts check (attempts >= 0),
    constraint ck_outbox_status_matches_type check (
        (event_type = 'INVOICE_PAID' and invoice_status in ('PAID', 'OVERPAID'))
        or (event_type = 'INVOICE_REOPENED' and invoice_status in ('OPEN', 'PARTIALLY_PAID')))
);

create index ix_outbox_unpublished on outbox_event (position) where published_at is null;

-- B24: every message that registers an invoice is recorded once per channel. A redelivered message hits
-- the primary key and changes nothing. The outcome of the first processing is kept for replies to retries.
create table processed_message (
    channel       varchar(16)  not null,
    message_id    varchar(64)  not null,
    invoice_id    uuid         not null references invoice (id),
    outcome       varchar(24)  not null,
    processed_at  timestamptz  not null,
    constraint pk_processed_message primary key (channel, message_id),
    constraint ck_processed_message_channel check (channel in ('KAFKA', 'REST')),
    constraint ck_processed_message_outcome check (outcome in ('REGISTERED', 'DUPLICATE_NUMBER'))
);

-- B26: ShedLock. A scheduled job runs on one instance at a time; the others skip it.
create table shedlock (
    name        varchar(64)  primary key,
    lock_until  timestamptz  not null,
    locked_at   timestamptz  not null,
    locked_by   varchar(255) not null
);
