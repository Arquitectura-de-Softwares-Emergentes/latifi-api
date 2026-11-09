# latifi-api
LatiFi API: user profile, reputation history, FX rates and on-chain event indexer (REST)

## Reputation

The score of an address runs from 0 to 1000 and is derived from the indexed event log, never stored as a running total. Each completed installment counts as one signal, so splitting a payment into dust does not raise it. The formula, its parameters and two worked examples are in [docs/reputation-ddd.md](docs/reputation-ddd.md).

## Exchange rates

`GET /api/v1/exchange-rate/convert` turns an amount of stablecoin base units (6 decimals, counted as one US dollar) into PEN, COP, MXN, ARS, BRL or CLP. `GET /api/v1/exchange-rate/rates` lists the six rates. Both routes need a session token, and the value is for display only: it never changes what is signed or stored on-chain.

The mobile app never calls a rate provider. Only the API does, behind the `ExchangeRateProvider` interface:

- The default adapter reads the open endpoint of ExchangeRate-API, `https://open.er-api.com/v6/latest/USD`. It is an aggregator, not a central bank. The free endpoint refreshes once a day and blocks callers that ask too often (HTTP 429, about 20 minutes), so the API caches the rates in PostgreSQL for one hour.
- Attribution: the terms of that endpoint ask for the text "Rates By Exchange Rate API" with a link to <https://www.exchangerate-api.com> wherever the rates are shown. The OpenAPI contract carries the text, and the clients must display it.
- A cache younger than one hour answers without a provider call. When the provider fails, times out or answers something invalid, the last cached rates are served with `stale: true`, and the provider is not called again for one minute. With no cached rate at all the answer is `503 RATE_UNAVAILABLE`. One refresh runs at a time, and it runs outside any database transaction.
- The adapter rejects the whole provider answer unless `result` is `success`, `base_code` is `USD` and every supported currency has a positive numeric rate.

| Variable | Default | Purpose |
| -------- | ------- | ------- |
| `LATIFI_FX_PROVIDER_URL` | `https://open.er-api.com/v6/latest/USD` | Rate source. A URL that holds `{apiKey}` makes the application refuse to start when the key is blank. |
| `LATIFI_FX_API_KEY` | empty | Optional provider key. Never commit it. Logs name only the host of the provider URL. |

Another provider needs one adapter that answers the same JSON shape (`result`, `base_code`, `time_last_update_unix`, `rates`) or a new implementation of `ExchangeRateProvider`. Do not turn on DEBUG for `org.springframework.web.client` in production when the key travels in the URL path.
