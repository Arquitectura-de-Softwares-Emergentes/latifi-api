@reputation
Feature: Reputation
  The score of an address is derived from the indexed chain events and from the profile. Each
  completed installment, each default and the completed profile is one entry of a ledger, and the
  API serves the score and the entries behind it.

  Scenario: An on-time installment raises the score
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And a payment of 341552511 units on loan 7 is emitted at block 14 with the block time 1802500000 and the flag onTime
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the reputation is score 520 and level BUILDING with 1 on time, 0 late and 0 defaults
    And the reputation has profileComplete false
    And the reputation was updated at the timestamp of block 14
    And the ledger row of the borrower carries the transaction hash and log index of the repayment of loan 7 and the score after 520

  @op-getReputation
  Scenario: A late installment lowers the score by the days late
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And a payment of 341552511 units on loan 7 is emitted at block 14 with the block time 1802851200 and the flag late
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the reputation is score 485 and level BUILDING with 0 on time, 1 late and 0 defaults
    When the wallet W reads the reputation history of "borrower" with the query ""
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the history item 1 is REPAID_LATE with delta -15 and score after 485
    And the history item 1 has 3 days late

  @op-getReputation
  Scenario: A late payment is capped at minus 100
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And a payment of 341552511 units on loan 7 is emitted at block 14 with the block time 1805184000 and the flag late
    When the indexer runs once
    And the wallet W reads the reputation history of "borrower" with the query ""
    Then the response status is 200
    And the history item 1 is REPAID_LATE with delta -100 and score after 400
    And the history item 1 has 30 days late

  @op-getReputation
  Scenario: A default lowers the score by 200
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And loan 7 defaults at block 14 with the block time 1803283200
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the reputation is score 300 and level NEW with 0 on time, 0 late and 1 defaults

  @op-getReputation
  Scenario: Ten on-time installments recover a default
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And loan 7 defaults at block 14 with the block time 1803283200
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the reputation is score 300 and level NEW with 0 on time, 0 late and 1 defaults
    Given 10 loans of 1000000000 units in one installment from loan 8 are requested, funded at the time 1810000000 and repaid on time from block 30
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    And the reputation is score 500 and level BUILDING with 10 on time, 0 late and 1 defaults

  @op-getReputation
  Scenario: Payments of one unit do not move the score
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And 50 payments of 1 units on loan 7 are emitted at block 14 with the block time 1802500000 and the flag onTime
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the reputation is score 500 and level NEW with 0 on time, 0 late and 0 defaults
    When the wallet W reads the reputation history of "borrower" with the query ""
    Then the history has 0 items out of 0 in total

  @op-getReputation
  Scenario: One unit paid on time and the rest paid late is a late installment
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And a payment of 1 units on loan 7 is emitted at block 14 with the block time 1802500000 and the flag onTime
    And a payment of 341552510 units on loan 7 is emitted at block 16 with the block time 1803024000 and the flag late
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the reputation is score 475 and level BUILDING with 0 on time, 1 late and 0 defaults
    When the wallet W reads the reputation history of "borrower" with the query ""
    Then the history has 1 item out of 1 in total
    And the history item 1 is REPAID_LATE with delta -25 and score after 475
    And the history item 1 has 5 days late

  @op-getReputation
  Scenario: A small loan cannot farm the score
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 30 blocks
    And loan 7 of 999999 units in 1 installment over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And loan 7 is repaid in full at block 14 with the block time 1802500000 and the flag onTime
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the reputation is score 500 and level NEW with 0 on time, 0 late and 0 defaults
    When the wallet W reads the reputation history of "borrower" with the query ""
    Then the history has 0 items out of 0 in total
    Given loan 8 of 999999 units in 1 installment over 90 days is requested at block 20
    And loan 8 is funded at the time 1800000000 at block 22
    And loan 8 defaults at block 24 with the block time 1808000000
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the reputation is score 300 and level NEW with 0 on time, 0 late and 1 defaults

  @op-getReputation @op-upsertProfile
  Scenario: Completing a profile adds the off-chain signal
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the wallet A saves its own profile with name "Ana Quispe" and EMAIL contact "ana@example.org"
    Then the response status is 201
    When the wallet A reads the reputation of "A"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the reputation is score 550 and level NEW with 0 on time, 0 late and 0 defaults
    And the reputation has profileComplete true
    When the wallet A reads the reputation history of "A" with the query ""
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the history has 1 item out of 1 in total
    And the history item 1 is PROFILE_COMPLETED with delta 50 and score after 550
    And the history item 1 has no transaction hash, no log index and no loan

  @op-getReputation
  Scenario: The first scored chain signal moves the level by score
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the wallet A saves its own profile with name "Ana Quispe" and EMAIL contact "ana@example.org"
    Then the response status is 201
    Given the borrower of the following loans is the wallet A
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And a payment of 341552511 units on loan 7 is emitted at block 14 with the block time 1802500000 and the flag onTime
    When the indexer runs once
    And the wallet A reads the reputation of "A"
    Then the reputation response conforms to the API contract
    And the reputation is score 570 and level BUILDING with 1 on time, 0 late and 0 defaults
    And the reputation has profileComplete true

  @op-getReputation
  Scenario: A borrower without a profile starts at 500
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the reputation is score 500 and level NEW with 0 on time, 0 late and 0 defaults
    And the reputation has profileComplete false
    And the reputation was updated at the timestamp of block 10
    When the wallet W reads the reputation history of "borrower" with the query ""
    Then the history has 0 items out of 0 in total

  @op-getReputation
  Scenario: An address with no profile and no loan answers 404
    Given the wallet W is generated at run time
    And the wallet W is logged in
    When the wallet W reads the reputation of "0x3333333333333333333333333333333333333333"
    Then the response status is 404
    And the reputation response conforms to the API contract
    And the response is an ApiError with code "REPUTATION_NOT_FOUND"
    When the client sends the Accept-Language header "es-419"
    And the wallet W reads the reputation of "0x3333333333333333333333333333333333333333"
    Then the response status is 404
    And the error message is "No existe reputación para esta dirección."

  @op-getReputation
  Scenario: A reorg that removes a repayment restores the earlier score
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And a payment of 341552511 units on loan 7 is emitted at block 14 with the block time 1802500000 and the flag onTime
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the reputation is score 520 and level BUILDING with 1 on time, 0 late and 0 defaults
    When the node reorgs from block 14
    And the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    And the reputation is score 500 and level NEW with 0 on time, 0 late and 0 defaults
    When the wallet W reads the reputation history of "borrower" with the query ""
    Then the history has 0 items out of 0 in total

  @op-getReputation
  Scenario: A reorg that removes the only loan removes the reputation
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 15
    When the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 200
    When the node reorgs from block 14
    And the indexer runs once
    And the wallet W reads the reputation of "borrower"
    Then the response status is 404
    And the response is an ApiError with code "REPUTATION_NOT_FOUND"

  @op-getReputationHistory
  Scenario: Recomputing keeps the entry ids
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    And loan 7 is funded at the time 1800000000 at block 12
    And a payment of 341552511 units on loan 7 is emitted at block 14 with the block time 1802500000 and the flag onTime
    And a payment of 341552511 units on loan 7 is emitted at block 15 with the block time 1805100000 and the flag onTime
    When the indexer runs once
    And the wallet W reads the reputation history of "borrower" with the query ""
    Then the history has 2 items out of 2 in total
    And the history ids are remembered
    When the indexer runs once
    And the reputation projection runs again for the borrower
    And the wallet W reads the reputation history of "borrower" with the query ""
    Then the history ids are the same as remembered

  @op-getReputationHistory
  Scenario: The history lists the events behind the score
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the wallet A saves its own profile with name "Ana Quispe" and EMAIL contact "ana@example.org"
    And three on-time installments and one late loan are indexed for the wallet A
    And the indexer runs once
    And the wallet A reads the reputation history of "A" with the query ""
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the history has 5 items out of 5 in total
    And the history item types are "REPAID_LATE,REPAID_ON_TIME,REPAID_ON_TIME,REPAID_ON_TIME,PROFILE_COMPLETED"
    And the history item 1 carries a transaction hash and a log index
    And the history item 2 carries a transaction hash and a log index
    And the history item 3 carries a transaction hash and a log index
    And the history item 4 carries a transaction hash and a log index
    And the history item 5 has no transaction hash, no log index and no loan
    And every history item continues the score of the next older item

  @op-getReputationHistory
  Scenario: The history filters by type and pages
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the wallet A saves its own profile with name "Ana Quispe" and EMAIL contact "ana@example.org"
    And three on-time installments and one late loan are indexed for the wallet A
    And the indexer runs once
    And the wallet A reads the reputation history of "A" with the query ""
    Then the history ids are remembered
    When the wallet A reads the reputation history of "A" with the query "type=REPAID_ON_TIME"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the history has 3 items out of 3 in total
    And the history item types are "REPAID_ON_TIME,REPAID_ON_TIME,REPAID_ON_TIME"
    When the wallet A reads the reputation history of "A" with the query "size=2&page=1"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the history has 2 items out of 5 in total
    And the history ids are the remembered ids from position 3 to 4

  @op-getReputationHistory
  Scenario Outline: Invalid history parameters are rejected
    Given the wallet W is generated at run time
    And the wallet W is logged in
    And the node has 20 blocks
    And loan 7 of 1000000000 units in 3 installments over 90 days is requested at block 10
    When the indexer runs once
    And the wallet W reads the reputation history of "borrower" with the query "<query>"
    Then the response status is 400
    And the reputation response conforms to the API contract
    And the response is an ApiError with code "VALIDATION_FAILED"

    Examples:
      | query     |
      | size=101  |
      | size=0    |
      | page=-1   |
      | type=NOPE |
      | size=abc  |

  @op-getReputation @op-getReputationHistory
  Scenario: Reputation needs a session
    When an anonymous client reads the reputation of "0x1111111111111111111111111111111111111111"
    Then the response status is 401
    And the reputation response conforms to the API contract
    And the response is an ApiError with code "UNAUTHORIZED"
    When an anonymous client reads the reputation history of "0x1111111111111111111111111111111111111111"
    Then the response status is 401
    And the reputation response conforms to the API contract
    And the response is an ApiError with code "UNAUTHORIZED"
