# Live integration check on Polygon Amoy

## Purpose

`AmoyLiveTest` is an optional smoke test. It reads real logs of the `LoanBook` contract from Polygon Amoy (chain id 80002), mirrors them into the test database with the same `IndexerService` that production uses, and then reads every mirrored loan and every borrower reputation back through the HTTP API. It checks that each answer is a 200 and matches the OpenAPI contract.

The test never signs and never sends a transaction. It holds no key. It only calls `eth_chainId`, `eth_blockNumber`, `eth_getLogs` and `eth_getBlockByNumber`.

A normal build skips it. The build never depends on it. The same cycle is replayed on a simulated node in CI by the scenario "The planned Amoy cycle is mirrored from fixtures" (`amoy_fixture.feature`), so the live check only adds proof against the real network.

## Prerequisites

The test needs at least one `LoanRequested` event in the block range it reads. Two ways to get one:

1. Run the full live cycle of the contracts repository (plan 03-08 of the Phase 3 checklist). It emits a request, a funding and two repayments.
2. Emit one event by hand, as described below.

You also need a Polygon Amoy node URL and a running Docker daemon, because the test uses Testcontainers for PostgreSQL like every other integration test.

## Environment variables

| Variable | Needed | Meaning |
| -------- | ------ | ------- |
| `LATIFI_LIVE` | yes | Must be `true`. Any other value skips the test. |
| `LATIFI_RPC_URL` | yes | URL of an Amoy node. A public one works, for example `https://polygon-amoy-bor-rpc.publicnode.com`. The test never prints it, only its host. |
| `LATIFI_INDEXER_START_BLOCK` | no | First block to read. Use the block of your first event. |
| `LATIFI_LIVE_LOOKBACK` | no | Blocks to read behind the head when no start block is set. The default is 2000. |
| `LATIFI_LOANBOOK_ADDRESS` | no | Another LoanBook address. The default is the one in `src/test/resources/deployments/amoy.json`. |

Never put a URL that carries an API key into a file of this repository.

## Run it

```
cd latifi-api
LATIFI_LIVE=true LATIFI_RPC_URL=https://polygon-amoy-bor-rpc.publicnode.com \
  LATIFI_INDEXER_START_BLOCK=<block of your first event> \
  ./mvnw -B test -Dtest=AmoyLiveTest
```

## Read the result

| Result | Meaning |
| ------ | ------- |
| passed | The range held events, the indexer mirrored them, and the API served every loan and borrower reputation with a 200 that matches the contract. The log has one summary line with the counts and the block range. |
| skipped | The variables were absent, the range held no `LoanBook` event, or it held events but no `LoanRequested` (start earlier), or the start block is above the head. The skip message names the range. |
| failed | A real problem: the node is not Amoy, the indexer skipped a cycle, the checkpoint did not reach the head, or an API answer was not a 200 or broke the contract. |

## Emit one event by hand

You do this with a keystore of your own. Claude never signs, funds or sends. Foundry keeps keystores in `~/.foundry/keystores`, and `cast` asks for the password at the prompt. The borrower account of the demo is `latifi-borrower`.

```
export LATIFI_RPC_URL=https://polygon-amoy-bor-rpc.publicnode.com
BOOK=$(jq -r .contracts.LoanBook.address ../latifi-contracts/deployments/amoy.json)
cast send $BOOK "requestLoan(uint256,uint16,uint32,uint8)" 100000000 1000 1 2 \
  --account latifi-borrower --rpc-url "$LATIFI_RPC_URL"
```

The command prints the block number of the transaction. Pass it as `LATIFI_INDEXER_START_BLOCK`, or a block shortly before it. The commands to fund and repay the loan are in `latifi-contracts/docs/DEPLOY-AMOY.md`. The whole cycle is plan 03-08.

The planned cycle uses a principal of 100000000, 1000 basis points, one day and two installments. Its interest is 27397, so the total due is 100027397, paid as 50013698 and then 50013699. After both payments the borrower has score 540 with two on-time installments.

## Warning about the backfill

The deployment block 51675536 sits about 680000 blocks behind a head near 52357686 (read on 2026-11-08). A provider that accepts only 10 blocks per `eth_getLogs` call needs tens of thousands of calls for that range, and that takes a long time. The log source halves a rejected range until the provider accepts it, but it cannot make the number of calls smaller.

So start from the block of the first event, never from the deployment block:

- for this test, set `LATIFI_INDEXER_START_BLOCK`
- for the deployment on Dokploy, set the same variable in the environment of the application

Without a start block, this test reads only the last 2000 blocks (`LATIFI_LIVE_LOOKBACK`), which is quick but can miss an older event. In that case it ends as skipped, not as passed.
