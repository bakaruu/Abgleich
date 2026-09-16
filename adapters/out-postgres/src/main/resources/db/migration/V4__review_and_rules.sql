-- Phase F2: rules R2-R6, human review, reversals and camt.054 enrichment.

-- Bank charges reported for a payment (B07).
alter table bank_transaction
    add column charges numeric(19,2),
    add constraint ck_transaction_charges check (charges is null or charges >= 0);

-- A matched credit the bank reversed (B10).
alter table bank_transaction drop constraint ck_transaction_status;
alter table bank_transaction
    add constraint ck_transaction_status
        check (status in ('UNMATCHED', 'PROPOSED', 'MATCHED', 'PARTIALLY_ALLOCATED', 'IGNORED', 'REVERSED'));

-- Allocations proposed together (one payment for several invoices, R6) share a group and are decided together.
-- Charges written off make a payment a little short settle the invoice (B07). A decision note keeps
-- the reason of a rejection or what reversed a confirmation.
alter table allocation
    add column group_id            uuid,
    add column charges_written_off numeric(19,2) not null default 0,
    add column decision_note       varchar(500),
    add constraint ck_allocation_charges check (charges_written_off >= 0);
update allocation set group_id = id where group_id is null;
alter table allocation alter column group_id set not null;

create index ix_allocation_group on allocation (group_id);
create index ix_allocation_transaction on allocation (transaction_id, status);
create index ix_allocation_invoice on allocation (invoice_id);
create index ix_transaction_review on bank_transaction (booking_date, id) where status = 'PROPOSED';
create index ix_transaction_reversal on bank_transaction (account_iban, amount)
    where status = 'MATCHED' and direction = 'CREDIT';
create index ix_invoice_reference on invoice (creditor_iban, reference) where reference is not null;
