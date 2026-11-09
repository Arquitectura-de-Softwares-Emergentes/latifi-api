-- Read model of the loans. One row per loan, rebuilt from the loan's own chain_event rows every
-- time the indexer inserts or removes one of them. Nothing writes here except that rebuild, so the
-- table can be dropped and recreated from chain_event at any time.
-- Amounts are uint256 values, hence numeric(78,0). Addresses are lowercase.
create table loan (
    loan_id           numeric(78,0) primary key,
    borrower          char(42)      not null,
    lender            char(42),
    principal         numeric(78,0) not null,
    interest_rate_bps integer       not null,
    term_days         integer       not null,
    installments      smallint      not null,
    status            varchar(10)   not null
        check (status in ('OPEN', 'ACTIVE', 'REPAID', 'DEFAULTED')),
    total_due         numeric(78,0),
    total_paid        numeric(78,0) not null default 0,
    installments_paid smallint      not null default 0,
    requested_at      timestamptz   not null,
    funded_at         timestamptz,
    final_due_date    timestamptz,
    next_due_date     timestamptz
);

-- The feed filters on status, borrower and lender, and sorts on the two time columns.
create index idx_loan_status on loan (status);
create index idx_loan_borrower on loan (borrower);
create index idx_loan_lender on loan (lender);
create index idx_loan_requested_at on loan (requested_at);
create index idx_loan_next_due_date on loan (next_due_date);
