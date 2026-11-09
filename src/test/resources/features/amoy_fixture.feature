@amoyfixture
Feature: Amoy fixture replay
  The planned live cycle of the contracts repository (principal 100000000, 1000 basis points, one
  day, two installments) replays on a simulated node that copies the Amoy deployment facts from
  deployments/amoy.json: the chain id 80002, the LoanBook address and the deployment block. The
  indexer and the API must show the same numbers that the live cycle is expected to produce.

  @op-getLoan @op-getReputation
  Scenario: The planned Amoy cycle is mirrored from fixtures
    Given the wallet R is generated at run time
    And the wallet R is logged in
    And a simulated node copies the Amoy chain id and deployment block of the fixture file
    And the planned Amoy cycle is emitted after the deployment block
    And a log of another contract is emitted in the blocks of the cycle
    When an indexer that starts at the deployment block runs until the head
    Then the indexer ended with no skip reason
    And chain_event has exactly 4 rows
    When the wallet R reads the loan "1"
    Then the response status is 200
    And the loan response conforms to the API contract
    And the loan response has the status REPAID, the total due "100027397" and the amount repaid "100027397"
    And the loan response has the field installmentsPaid with the value "2"
    And the loan response has no field nextDueDate
    And the loan response has the field borrower with the value "0x1111111111111111111111111111111111111111"
    And the loan response has the field lender with the value "0x2222222222222222222222222222222222222222"
    When the wallet R reads the reputation of "0x1111111111111111111111111111111111111111"
    Then the response status is 200
    And the reputation response conforms to the API contract
    And the reputation is score 540 and level BUILDING with 2 on time, 0 late and 0 defaults
