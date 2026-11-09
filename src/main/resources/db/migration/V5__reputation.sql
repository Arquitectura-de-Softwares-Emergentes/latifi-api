-- Reputation of an address, derived from the indexed chain events and the profile. Both tables are
-- rebuilt by a full recompute of the address, so they can be dropped and filled again at any time.
-- Addresses are lowercase. Amounts that come from the chain are uint256 values, hence numeric(78,0).

-- The ledger: one row per entry that moved the score. source_key is "profile" for the profile
-- entry and "tx:<hash>:<logIndex>" for a chain entry, so a recompute updates the same row and the
-- id that clients saw stays valid.
create table reputation_event (
    id          bigserial     primary key,
    address     char(42)      not null,
    source_key  varchar(100)  not null,
    seq         integer       not null,
    type        varchar(20)   not null
        check (type in ('PROFILE_COMPLETED', 'REPAID_ON_TIME', 'REPAID_LATE', 'DEFAULTED')),
    loan_id     numeric(78,0),
    delta       integer       not null,
    score_after integer       not null check (score_after between 0 and 1000),
    days_late   integer,
    tx_hash     char(66),
    log_index   integer,
    occurred_at timestamptz   not null,
    unique (address, source_key)
);

create index idx_reputation_event_address_seq on reputation_event (address, seq);

-- The current score of an address. level_rank is the position of the level (0 NEW to 3 EXCELLENT),
-- stored so the loan feed can filter and sort on it with plain integers.
create table reputation_state (
    address          char(42)     primary key,
    score            integer      not null check (score between 0 and 1000),
    level            varchar(10)  not null,
    level_rank       smallint     not null,
    on_time_count    integer      not null,
    late_count       integer      not null,
    default_count    integer      not null,
    profile_complete boolean      not null,
    updated_at       timestamptz  not null
);

create index idx_reputation_state_level_rank on reputation_state (level_rank);
create index idx_reputation_state_score on reputation_state (score);
