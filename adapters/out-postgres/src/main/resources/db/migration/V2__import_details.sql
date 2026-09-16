-- Data read from bank files that V1 had no place for. V1 is applied and never edited.

-- Balance dates, shown with the import result and needed to detect overlapping periods.
alter table statement_import
    add column opening_date date not null,
    add column closing_date date not null;

-- One row per payment: a batch entry (B09) becomes several rows whose dedup_key ends in "#n".
alter table bank_transaction
    add column bank_reference    varchar(35),
    add column end_to_end_id     varchar(35),
    add column counterparty_name varchar(140),                -- needed by name-based matching (R5)
    add column reversal          boolean not null default false; -- B10

create index ix_import_file on statement_import (file_sha256);
