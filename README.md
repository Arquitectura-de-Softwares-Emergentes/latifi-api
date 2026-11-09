# latifi-api
LatiFi API: user profile, reputation history, FX rates and on-chain event indexer (REST)

## Reputation

The score of an address runs from 0 to 1000 and is derived from the indexed event log, never stored as a running total. Each completed installment counts as one signal, so splitting a payment into dust does not raise it. The formula, its parameters and two worked examples are in [docs/reputation-ddd.md](docs/reputation-ddd.md).
