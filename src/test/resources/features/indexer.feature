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
