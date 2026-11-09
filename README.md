# latifi-api
LatiFi API: user profile, reputation history, FX rates and on-chain event indexer (REST)

## What it does

LatiFi API is the off-chain backend of LatiFi, a peer to peer lending app on Polygon Amoy. Wallets sign in by signing a challenge, keep a small profile, and read a loan feed and a reputation score that the API derives from the events of the `LoanBook` contract. An indexer copies those events into PostgreSQL, and every read endpoint serves that copy. The API never signs and never sends a transaction. The mobile app also reads display-only exchange rates through it.

## Endpoints

Every path lives under `/api/v1`. The contract is `openapi/latifi-api.v0.yaml` (version 0.2.0), and a test fails when the code and the contract differ.

| Operation | Method and path | Auth | Feature file |
| --------- | --------------- | ---- | ------------ |
| `issueChallenge` | `POST /auth/challenge` | none | `auth.feature` |
| `verifyChallenge` | `POST /auth/verify` | none | `auth.feature` |
| `getProfile` | `GET /profiles/{address}` | optional bearer token | `profiles.feature` |
| `upsertProfile` | `PUT /profiles/{address}` | bearer token | `profiles.feature` |
| `getReputation` | `GET /reputation/{address}` | bearer token | `reputation.feature` |
| `getReputationHistory` | `GET /reputation/{address}/history` | bearer token | `reputation.feature` |
| `listLoans` | `GET /loans` | bearer token | `loans.feature` |
| `getLoan` | `GET /loans/{loanId}` | bearer token | `loans.feature` |
| `convertAmount` | `GET /exchange-rate/convert` | bearer token | `fx.feature` |
| `listRates` | `GET /exchange-rate/rates` | bearer token | `fx.feature` |

The session token lives 15 minutes and there is no refresh token. When it expires, the wallet signs a new challenge.

## Run it

You need JDK 21 and a running Docker daemon for the tests. Testcontainers starts PostgreSQL 16, so no database or datasource variable is needed:

```
./mvnw -B verify
```

To start the API you need a PostgreSQL database and two required variables, the datasource password and the signing secret for session tokens:

```
export SPRING_DATASOURCE_PASSWORD=...
export LATIFI_JWT_SECRET=...   # at least 32 bytes
./mvnw spring-boot:run
```

Swagger UI is at `http://localhost:8080/swagger-ui.html` and the generated document at `/v3/api-docs`. Both are public. Set `LATIFI_SWAGGER_ENABLED=false` to turn them off. The contract file `openapi/latifi-api.v0.yaml` stays the source of truth.

## Configuration

Every environment variable that `src/main/resources/application.yml` reads is in this table. A test fails when a variable is missing here.

| Variable | Default | Meaning |
| -------- | ------- | ------- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/latifi` | JDBC URL of PostgreSQL. |
| `SPRING_DATASOURCE_USERNAME` | `latifi` | Database user. |
| `SPRING_DATASOURCE_PASSWORD` | none, required | Database password. Never commit it. |
| `LATIFI_JWT_SECRET` | none, required | Signing secret of the session tokens, at least 32 bytes. |
| `LATIFI_AUTH_DOMAIN` | `localhost` | Domain written into the sign-in message. |
| `LATIFI_AUTH_URI` | `http://localhost:8080` | URI written into the sign-in message. |
| `LATIFI_TRUST_FORWARDED_FOR` | `false` | Set it to `true` behind the reverse proxy, so the rate limit reads the client address from `X-Forwarded-For`. |
| `LATIFI_RPC_URL` | empty | Polygon Amoy node URL. With no value the API starts and the indexer stays off. The URL can carry a key, so it never goes into the repository and logs show the host only. |
| `LATIFI_LOANBOOK_ADDRESS` | `0xccb8ad15a4954138c987a77924ceab468a843266` | Address of the `LoanBook` contract (release v0.2.0). |
| `LATIFI_INDEXER_START_BLOCK` | `51675536` | First block the indexer scans when it has no checkpoint. See the advice in the Indexer section. |
| `LATIFI_FX_PROVIDER_URL` | `https://open.er-api.com/v6/latest/USD` | Rate source. A URL that holds `{apiKey}` makes the application refuse to start when the key is blank. |
| `LATIFI_FX_API_KEY` | empty | Optional provider key. Never commit it. Logs name only the host of the provider URL. |
| `LATIFI_SWAGGER_ENABLED` | `true` | Serves Swagger UI and `/v3/api-docs`. Set it to `false` to hide them. |

## Indexer

The indexer polls the node every 5 seconds, reads logs of the `LoanBook` contract in batches of up to 500 blocks, and starts every cycle 12 blocks behind its checkpoint, so a short reorganization rewrites the affected rows. A reorganization deeper than 12 blocks is not repaired. When the provider rejects a range, the indexer halves it and tries again. Choose the start block with care: use the block of the first event you care about. A backfill from the deployment block 51675536 is slow on providers that limit `eth_getLogs` to 10 blocks per call, because it takes thousands of calls. The optional live check is explained in [docs/LIVE-INTEGRATION.md](docs/LIVE-INTEGRATION.md).

## Reputation

The score of an address runs from 0 to 1000 and is derived from the indexed event log, never stored as a running total. Each completed installment counts as one signal, so splitting a payment into dust does not raise it. The formula, its parameters and two worked examples are in [docs/reputation-ddd.md](docs/reputation-ddd.md).

## Exchange rates

`GET /api/v1/exchange-rate/convert` turns an amount of stablecoin base units (6 decimals, counted as one US dollar) into PEN, COP, MXN, ARS, BRL or CLP. `GET /api/v1/exchange-rate/rates` lists the six rates. Both routes need a session token, and the value is for display only: it never changes what is signed or stored on-chain.

The mobile app never calls a rate provider. Only the API does, behind the `ExchangeRateProvider` interface:

- The default adapter reads the open endpoint of ExchangeRate-API, `https://open.er-api.com/v6/latest/USD`. It is an aggregator, not a central bank. The free endpoint refreshes once a day and blocks callers that ask too often (HTTP 429, about 20 minutes), so the API caches the rates in PostgreSQL for one hour.
- Attribution: the terms of that endpoint ask for the text "Rates By Exchange Rate API" with a link to <https://www.exchangerate-api.com> wherever the rates are shown. The OpenAPI contract carries the text, and the clients must display it.
- A cache younger than one hour answers without a provider call. When the provider fails, times out or answers something invalid, the last cached rates are served with `stale: true`, and the provider is not called again for one minute. With no cached rate at all the answer is `503 RATE_UNAVAILABLE`. One refresh runs at a time, and it runs outside any database transaction.
- The adapter rejects the whole provider answer unless `result` is `success`, `base_code` is `USD` and every supported currency has a positive numeric rate.

`LATIFI_FX_PROVIDER_URL` and `LATIFI_FX_API_KEY` are described in the Configuration table. Another provider needs one adapter that answers the same JSON shape (`result`, `base_code`, `time_last_update_unix`, `rates`) or a new implementation of `ExchangeRateProvider`. Do not turn on DEBUG for `org.springframework.web.client` in production when the key travels in the URL path.

## Tests

`./mvnw -B verify` runs unit tests, integration tests and the Cucumber suite. Each scenario carries tags: a feature tag such as `@auth` or `@loans`, and one `@op-<operationId>` tag for every operation it exercises. `FeatureCoverageTest` fails when an operation of the contract has no tagged scenario, or when a tag names an operation that the contract does not define. `OpenApiCoverageTest` compares the generated documentation with the contract, and `ReadmeEnvTest` checks the Configuration table.

Scenarios tagged `@live` stay out of the default run through `cucumber.filter.tags=not @live` in `junit-platform.properties`. The Amoy fixture scenario (`@amoyfixture`) replays the planned live cycle on a simulated node and runs in CI. The real network check is `AmoyLiveTest`, which a normal run skips: it needs `LATIFI_LIVE=true` and `LATIFI_RPC_URL`, never signs, and never sends a transaction.
