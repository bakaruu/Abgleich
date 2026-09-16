-- Abgleich initial schema.
-- The database is the last line of defence: every constraint below maps to a bug ID
-- from the catalogue, so a code bug cannot silently corrupt money or duplicate data.

create table statement_import (
    id            uuid          primary key,
    source        varchar(16)   not null,             -- WEB, REST, SFTP, BANK_API
    format        varchar(24)   not null,             -- CAMT053_V04, NORMA43, ...
    file_sha256   char(64)      not null,
    message_id    varchar(35),                        -- camt MsgId; null for Norma 43
    account_iban  varchar(34)   not null,
    currency      char(3)       not null,
    opening_bal   numeric(19,2) not null,
    closing_bal   numeric(19,2) not null,
    status        varchar(16)   not null,
    failure       varchar(500),
    received_at   timestamptz   not null,
    constraint uq_import_file unique (account_iban, file_sha256)                    -- B21
);

create unique index uq_import_message
    on statement_import (account_iban, message_id)
    where message_id is not null;                                                   -- B21

create table bank_transaction (
    id              uuid          primary key,
    import_id       uuid          not null references statement_import (id),
    account_iban    varchar(34)   not null,
    dedup_key       varchar(128)  not null,           -- AcctSvcrRef or derived key (B16)
    booking_date    date          not null,           -- accounting dates are local (B15)
    value_date      date          not null,
    direction       varchar(6)    not null,
    amount          numeric(19,2) not null,
    currency        char(3)       not null,
    reference       varchar(35),                      -- text keeps leading zeros (B04)
    remittance_text varchar(500),
    status          varchar(24)   not null,
    version         bigint        not null default 0, -- optimistic locking (B22)
    constraint ck_transaction_direction check (direction in ('CREDIT', 'DEBIT')),   -- B10
    constraint ck_transaction_amount_positive check (amount > 0),                   -- B10
    constraint uq_transaction_dedup unique (account_iban, dedup_key)                -- B12, B16
);

create index ix_transaction_unmatched
    on bank_transaction (account_iban, booking_date)
    where status = 'UNMATCHED';

create table invoice (
    id             uuid          primary key,
    invoice_number varchar(35)   not null,
    creditor_iban  varchar(34)   not null,
    debtor_name    varchar(140)  not null,
    amount         numeric(19,2) not null,
    currency       char(3)       not null,
    reference      varchar(35),
    due_date       date          not null,
    status         varchar(24)   not null,
    version        bigint        not null default 0,  -- B22
    constraint ck_invoice_amount_positive check (amount > 0),
    constraint uq_invoice_number unique (invoice_number)                            -- B24
);

create index ix_invoice_open
    on invoice (creditor_iban, currency)
    where status in ('OPEN', 'PARTIALLY_PAID');

create table allocation (
    id             uuid          primary key,
    transaction_id uuid          not null references bank_transaction (id),
    invoice_id     uuid          not null references invoice (id),
    amount         numeric(19,2) not null,
    rule           varchar(8)    not null,
    confidence     numeric(3,2)  not null,
    explanation    varchar(500)  not null,
    status         varchar(16)   not null,            -- PROPOSED, CONFIRMED, REJECTED, REVERSED
    decided_by     varchar(64),
    decided_at     timestamptz,
    created_at     timestamptz   not null,
    version        bigint        not null default 0,  -- B34
    constraint ck_allocation_amount_positive check (amount > 0),
    constraint ck_allocation_confidence check (confidence between 0 and 1),
    constraint ck_allocation_status check (status in ('PROPOSED', 'CONFIRMED', 'REJECTED', 'REVERSED'))
);

create unique index uq_allocation_active
    on allocation (transaction_id, invoice_id)
    where status in ('PROPOSED', 'CONFIRMED');                                      -- B33
