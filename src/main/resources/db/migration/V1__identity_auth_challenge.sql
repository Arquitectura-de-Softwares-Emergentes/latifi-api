-- Wallet sign-in challenges. The server keeps the exact text it issued and
-- verifies the signature against that text, never against client data.
create table auth_challenge (
    id         uuid primary key,
    address    char(42)     not null,
    nonce      varchar(64)  not null,
    message    text         not null,
    issued_at  timestamptz  not null,
    expires_at timestamptz  not null,
    used_at    timestamptz  null
);

create index idx_auth_challenge_expires_at on auth_challenge (expires_at);
create index idx_auth_challenge_address on auth_challenge (address);
