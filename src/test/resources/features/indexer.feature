@indexer
Feature: Event indexer
  The API reads the LoanBook events from the chain and mirrors them into its own database. Every
  event lands once, and a restart loses nothing and repeats nothing.

  @api-03
  Scenario: A LoanRequested event on the node is mirrored once
    Given the node has 20 blocks
    And a LoanRequested log for loan 7 is emitted at block 15
    When the indexer runs once
    Then chain_event has exactly 1 row
    And the stored row is the LoanRequested log of loan 7 at block 15 with the block timestamp of the node
    And the checkpoint is block 20
    And the eth_getLogs filter sent the four topic0 values as one array of alternatives
    When the node request log is cleared
    And the indexer runs once
    Then chain_event has exactly 1 row
    And the checkpoint is block 20
    And the node served an eth_getLogs range that starts at block 9

  @api-03
  Scenario: Restarting the indexer loses and duplicates nothing
    Given the node has 30 blocks
    And a LoanRequested log for loan 1 is emitted at block 5
    And a LoanRequested log for loan 2 is emitted at block 12
    And a LoanRequested log for loan 3 is emitted at block 22
    When the indexer runs once
    And the indexer restarts as a new service instance
    And a LoanRequested log for loan 4 is emitted at block 33
    And a LoanRequested log for loan 5 is emitted at block 35
    And the indexer runs once
    Then chain_event has exactly 5 rows
    And chain_event holds the loans "1,2,3,4,5"
    And no chain_event key appears twice
    And the checkpoint is block 35

  @api-03
  Scenario: Events emitted while the indexer is stopped appear after it restarts
    Given the node has 10 blocks
    And a LoanRequested log for loan 1 is emitted at block 4
    When the indexer runs once
    Then chain_event holds the loans "1"
    When a LoanRequested log for loan 2 is emitted at block 14
    And a LoanDefaulted log for loan 1 is emitted at block 16
    And the indexer restarts as a new service instance
    And the indexer runs once
    Then chain_event has exactly 3 rows
    And chain_event holds the loans "1,1,2"
    And the checkpoint is block 16

  @api-03
  Scenario: A log delivered twice by the node is stored once
    Given the node has 8 blocks
    And the node repeats every log in one answer
    And a LoanRequested log for loan 1 is emitted at block 3
    And a LoanRequested log for loan 2 is emitted at block 6
    When the indexer runs once
    Then chain_event has exactly 2 rows
    And no chain_event key appears twice

  @api-03
  Scenario: Every event type is mirrored
    Given the node has 10 blocks
    And a LoanRequested log for loan 9 is emitted at block 2
    And a LoanFunded log for loan 9 is emitted at block 3
    And a LoanRepaid log for loan 9 is emitted at block 4
    And a LoanDefaulted log for loan 9 is emitted at block 5
    When the indexer runs once
    Then chain_event has exactly 4 rows
    And the chain_event row of type LOAN_REQUESTED for loan 9 has the payload field "borrower" equal to "0x1111111111111111111111111111111111111111"
    And the chain_event row of type LOAN_REQUESTED for loan 9 has the payload field "installments" equal to "3"
    And the chain_event row of type LOAN_FUNDED for loan 9 has the payload field "lender" equal to "0x2222222222222222222222222222222222222222"
    And the chain_event row of type LOAN_FUNDED for loan 9 has the payload field "dueDate" equal to "1767776100"
    And the chain_event row of type LOAN_REPAID for loan 9 has the payload field "amountPaid" equal to "1800000"
    And the chain_event row of type LOAN_REPAID for loan 9 has the payload field "onTime" equal to "true"
    And the chain_event row of type LOAN_DEFAULTED for loan 9 has the payload field "outstandingAmount" equal to "3000000"
    And the chain_event row of type LOAN_DEFAULTED for loan 9 has the payload field "borrower" equal to "0x1111111111111111111111111111111111111111"

  @api-03
  Scenario: Listeners see the loans touched by a batch
    Given the node has 25 blocks
    And a LoanRequested log for loan 7 is emitted at block 3
    And a LoanRequested log for loan 8 is emitted at block 5
    And a LoanRequested log for loan 9 is emitted at block 22
    When the indexer runs once
    Then the listener received 2 batches
    And batch 1 touched the loans "7,8"
    And batch 2 touched the loans "9"
    When the indexer runs once
    Then the listener received 2 batches
