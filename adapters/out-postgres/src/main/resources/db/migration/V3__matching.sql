-- Matching with rule R1: invoices track what was paid, and every status column only accepts known values.

alter table invoice
    add column paid_amount numeric(19,2) not null default 0,
    add constraint ck_invoice_paid_not_negative check (paid_amount >= 0),
    add constraint ck_invoice_status
        check (status in ('OPEN', 'PARTIALLY_PAID', 'PAID', 'OVERPAID', 'CANCELLED'));

alter table bank_transaction
    add constraint ck_transaction_status
        check (status in ('UNMATCHED', 'PROPOSED', 'MATCHED', 'PARTIALLY_ALLOCATED', 'IGNORED'));

alter table allocation
    add constraint ck_allocation_rule check (rule in ('R1', 'R2', 'R3', 'R4', 'R5', 'R6')),
    -- A proposal has no decider yet; a decided allocation always says who decided and when (B34).
    add constraint ck_allocation_decision
        check ((status = 'PROPOSED') = (decided_by is null and decided_at is null));

create index ix_transaction_unmatched_credit
    on bank_transaction (account_iban, booking_date, dedup_key)
    where status = 'UNMATCHED' and direction = 'CREDIT';
