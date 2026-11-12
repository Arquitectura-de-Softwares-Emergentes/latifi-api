-- One profile per wallet address. The address is stored in lowercase, so the checksummed form
-- and the lowercase form of an address reach the same row.
create table profile (
    address       char(42)     primary key,
    display_name  varchar(80)  not null,
    contact_type  varchar(8)   not null check (contact_type in ('EMAIL', 'PHONE')),
    contact_value varchar(254) not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null
);
