-- The indexer keeps one row here: the last block it scanned. It moves in the same transaction as
-- the inserts into chain_event, so a crash loses nothing and repeats nothing.
create table indexer_checkpoint (
    id         smallint    primary key check (id = 1),
    last_block bigint      not null,
    updated_at timestamptz not null
);

-- One row per contract log. The pair (tx_hash, log_index) identifies a log on the chain, so a log
-- that the indexer reads twice lands on the same row. Addresses inside payload are lowercase and
-- every uint256 travels as decimal text.
create table chain_event (
    id              bigserial     primary key,
    tx_hash         char(66)      not null,
    log_index       integer       not null,
    block_number    bigint        not null,
    block_hash      char(66)      not null,
    block_timestamp timestamptz   not null,
    event_type      varchar(16)   not null
        check (event_type in ('LOAN_REQUESTED', 'LOAN_FUNDED', 'LOAN_REPAID', 'LOAN_DEFAULTED')),
    loan_id         numeric(78,0) not null,
    payload         jsonb         not null,
    constraint uq_chain_event unique (tx_hash, log_index)
);

create index idx_chain_event_block_number on chain_event (block_number);
create index idx_chain_event_loan_id on chain_event (loan_id);
