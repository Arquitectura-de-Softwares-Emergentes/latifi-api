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
