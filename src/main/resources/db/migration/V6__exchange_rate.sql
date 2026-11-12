-- Last known exchange rate per local currency, in units of the currency per one US dollar. The
-- table is derived data: the rate service refills it from the provider, so it can be emptied at
-- any time. as_of is the instant the provider gave for the rate, fetched_at is our clock when we
-- stored it and decides when the row is old enough to refresh.
create table exchange_rate_cache (
    currency   char(3)        primary key,
    rate       numeric(24,10) not null check (rate > 0),
    as_of      timestamptz    not null,
    fetched_at timestamptz    not null
);
