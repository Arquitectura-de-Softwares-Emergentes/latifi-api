# Reputation: domain description

This file describes how LatiFi API computes the reputation score of a wallet address. It is the
reference for the code of plan 04-06 and for the report. The OpenAPI contract
(`openapi/latifi-api.v0.yaml`, version 0.2.0) defines the shape of the answers. This file defines
the numbers.

## Purpose

The reputation context turns two sources into one score per address: the indexed event log of the
`LoanBook` contract and the off-chain profile. The score is derived (decision D-09). The API never
stores it as a running total that later events increment, and it never reads the counters of the
contract. Each time the score is needed, it is the result of a fold over the ledger of the address.
Because of that, two runs over the same events give the same number, and a chain reorganization
cannot leave a wrong score behind.

## Ubiquitous language

| Term | Meaning |
| ---- | ------- |
| Signal | A fact that can move the score: a completed profile, an installment paid on time, an installment paid late, a default. |
| Policy | The set of parameters that turn a signal into a delta. They live in configuration. |
| Ledger | The ordered list of entries of one address. Each entry is one signal with its delta and the score after it. |
| Score | An integer from 0 to 1000. |
| Level | A label for a range of the score: NEW, BUILDING, TRUSTED or EXCELLENT. |
| Installment | One of the N equal parts of a loan. N is between 1 and 6. |
| Completed installment | A payment that leaves the cumulative paid amount exactly on the target of that installment. |

## Signals

The API exposes four signal types. Three come from the chain and one from the profile.

| Type | Source | Notes |
| ---- | ------ | ----- |
| `PROFILE_COMPLETED` | The profile row of the address (name and contact both present) | No transaction. `loanId`, `txHash` and `logIndex` are null in the API. |
| `REPAID_ON_TIME` | A `LoanRepaid` event that completes an installment, with `onTime` true | One entry per completed installment. |
| `REPAID_LATE` | A `LoanRepaid` event that completes an installment, with `onTime` false | One entry per completed installment, with days late. |
| `DEFAULTED` | A `LoanDefaulted` event | One entry per default. |

`LoanRepaid` carries neither the borrower nor a time. The projection joins the loan (from the
`LoanRequested` event) to find the borrower, and it reads the timestamp of the block that holds the
event. If the `onTime` flag of the event and a comparison of the block timestamp with the due time
ever disagree, the flag of the event decides, because the contract computed it with the same block
timestamp that the chain recorded.

A loan whose `LoanRequested` event has not been indexed yet cannot be assigned to a borrower. Its
payments stay pending and enter the ledger when the request arrives.

## Why one signal per completed installment

Review item WR-01 of Phase 3 points out that the contract increments `onTime` or `late` once per
payment. A borrower can split one installment into payments of one base unit and raise the counter
by thousands, paying only gas. The contract is frozen, so the fix cannot be a contract change.

The reputation context closes the gap in its own reading of the log. It counts completed
installments, not payments. The contracts and the event ABI stay as they are.

The rules for payments that do not complete an installment:

- A partial payment that does not complete the installment moves nothing.
- The installment counts when a payment brings the cumulative paid amount exactly to the target of
  that installment. That payment decides the signal. If it arrives on or before the due time, every
  earlier payment of the installment did too, so the installment is on time. If it arrives after the
  due time, the installment is late, even when earlier parts were paid on time. One unit paid on
  time and the rest paid late is a late installment.

The schedule that defines the targets comes from the contract:

- `totalDue` is split into N installments. `base = floor(totalDue / N)`.
- Installments 1 to N minus 1 each equal `base`. The last installment is `totalDue - (N - 1) * base`
  and absorbs the remainder.
- Installment k falls due at `fundedAt + k * termDays * 86400 / N` seconds, with integer division.
  Installment N falls due exactly at `fundedAt + termDays * 86400`.
- When `totalDue` is smaller than N, `base` is 0 (the dust case). Every unit sits on the last
  installment, so the loan has exactly one completed installment, and the minimum scored principal
  below also applies.

## Policy

| Parameter | Configuration key | Default | Meaning |
| --------- | ----------------- | ------- | ------- |
| Start score | `latifi.reputation.start-score` | 500 | Score of an address before any entry. |
| On time delta | `latifi.reputation.on-time-delta` | 20 | Points added for a completed installment paid on time. |
| Late penalty per day | `latifi.reputation.late-per-day` | 5 | Points removed for each day late. |
| Late cap | `latifi.reputation.late-cap` | 100 | Largest total penalty of one late installment. |
| Default penalty | `latifi.reputation.default-delta` | 200 | Points removed for a default. |
| Profile bonus | `latifi.reputation.profile-bonus` | 50 | Points added once, when the profile is complete. |
| Minimum scored principal | `latifi.reputation.min-scored-principal` | 1000000 | Smallest principal, in base units, whose on-time installments score. |

The range 0 to 1000 is fixed in code. The other values are configuration, so they can change
without a code change.

Two of the values are decisions of the phase, not of the original context, and the project owner confirmed
them: the profile bonus of 50 and the minimum scored principal of 1000000 base units (one token).
The minimum scored principal blocks the farm of many tiny loans. An on-time installment of a loan
whose principal is below it creates no ledger entry. Late and default signals of such loans still
count, so a borrower cannot hide a bad record behind a small loan.

## The fold

Entries are ordered by these keys, in this order:

1. `occurredAt`, the profile timestamp or the block timestamp.
2. Source rank: 0 for the profile, 1 for chain events.
3. Block number.
4. Log index.

The fold starts at the start score. Each entry applies

```
score = clamp(score + delta, 0, 1000)
```

and the ledger stores the effective delta, `scoreAfter - scoreBefore`, so that
`scoreAfter = scoreBefore + delta` always holds for the numbers a client sees. A signal at the
ceiling or the floor can therefore show a delta of 0 or a smaller value than the policy says.

The delta of a late installment is

```
delta = -min(late-per-day * daysLate, late-cap)
daysLate = max(1, ceil((blockTimestamp - dueAt) / 86400))
```

The `max(1, ...)` makes a payment that is late by one second count as one day.

## Levels

| Level | Score range |
| ----- | ----------- |
| NEW | 0 to 399 |
| BUILDING | 400 to 599 |
| TRUSTED | 600 to 799 |
| EXCELLENT | 800 to 1000 |

These are the levels of the report multiplied by ten. An address with no scored chain signal is NEW
whatever its score. A fresh address with a complete profile has 550, but it stays NEW until a
completed installment, a late payment or a default exists.

## Counters

`onTimeCount`, `lateCount` and `defaultCount` in the API count ledger entries of the matching type.
They are not the counters of the contract, and nobody should compare the two sets. The contract
counts payments and the API counts completed installments, which is the point of the rule above.

## Determinism and reorgs

- The fold never calls `Instant.now()`. The same ledger gives the same score on any day.
- The score does not decay with elapsed time. A decay would make the result depend on the moment of
  the call.
- After a reorganization, the projection removes the entries of the dropped blocks and folds again
  from the event log. The result equals the score the address would have if the removed event had
  never existed.
- A loan with no indexed request keeps its payments pending until the request arrives. Then the
  fold runs again.

## Worked examples

Every row is recomputed by an independent script, and plan 04-06 replays both tables in a unit
test. The policy is the default one.

Example A: a normal history with a late payment, a default and a recovery (requirement REP-04).

| Step | Signal | Days late | Delta | Score after |
| ---- | ------ | --------- | ----- | ----------- |
| 0 | START | | | 500 |
| 1 | PROFILE_COMPLETED | | +50 | 550 |
| 2 | REPAID_ON_TIME | | +20 | 570 |
| 3 | REPAID_LATE | 3 | -15 | 555 |
| 4 | REPAID_ON_TIME | | +20 | 575 |
| 5 | DEFAULTED | | -200 | 375 |
| 6 | REPAID_ON_TIME | | +20 | 395 |
| 7 | REPAID_ON_TIME | | +20 | 415 |
| 8 | REPAID_ON_TIME | | +20 | 435 |
| 9 | REPAID_ON_TIME | | +20 | 455 |
| 10 | REPAID_ON_TIME | | +20 | 475 |
| 11 | REPAID_ON_TIME | | +20 | 495 |
| 12 | REPAID_ON_TIME | | +20 | 515 |
| 13 | REPAID_ON_TIME | | +20 | 535 |
| 14 | REPAID_ON_TIME | | +20 | 555 |
| 15 | REPAID_ON_TIME | | +20 | 575 |

Example B: the ceiling and the floor. Step 0 and step 4 start two separate runs.

| Step | Signal | Days late | Delta | Score after |
| ---- | ------ | --------- | ----- | ----------- |
| 0 | START | | | 990 |
| 1 | REPAID_ON_TIME | | +10 | 1000 |
| 2 | REPAID_ON_TIME | | 0 | 1000 |
| 3 | REPAID_LATE | 30 | -100 | 900 |
| 4 | START | | | 100 |
| 5 | DEFAULTED | | -100 | 0 |
| 6 | DEFAULTED | | 0 | 0 |

In step 1 of example B the policy delta is +20, but the clamp leaves +10. In step 3, thirty days
late would cost 150 points, and the cap holds it at 100. In step 5, the policy delta is -200 and
the clamp leaves -100.

## What is not scored

- Elapsed time. There is no decay and no bonus for age.
- Overdue installments without a `LoanDefaulted` event. Until someone marks the loan, the contract
  has not declared a default, so the score does not either.
- Read-only activity, such as viewing loans or profiles.
- The counters of the contract.

An open request has no expiry on the chain (review item WR-02), so a request can wait forever. The
API does not score that. The feed handles it with filters: lenders use `status=OPEN` and
`sort=recent`.

## Requirements and stories

| Item | Where it is covered |
| ---- | ------------------- |
| REP-01 (score from loan behavior) | Signals, The fold |
| REP-02 (levels and counters) | Levels, Counters |
| REP-04 (recovery after a default) | Worked examples, example A |
| API-01 (reputation and history reads) | Signals, Counters, and the contract operations `getReputation` and `getReputationHistory` |
| D-09 (derived score, not stored increments) | Purpose, Determinism and reorgs |
| D-10 (range, start, deltas, parameters in configuration) | Policy, The fold |

## Using this text in the report

Chapter V of the report and its level table still use a scale of 0 to 100. They must change to the
scale of 0 to 1000 described here. A person makes that edit in Phase 8, citing this file
(`latifi-api/docs/reputation-ddd.md`).
