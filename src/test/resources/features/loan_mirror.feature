@loanmirror
Feature: Loan mirror
  The API keeps one row per loan with its current status, amounts and due dates. The row follows
  the loan's events and always equals what a fresh read of those events gives.

  @api-03
  Scenario: A requested loan is mirrored as an open loan
    Given the node has 20 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 12
    When the indexer runs once
    Then the loan table has 1 row
    And the loan 7 has these values:
      | status            | OPEN                                       |
      | borrower          | 0x1111111111111111111111111111111111111111 |
      | lender            | null                                       |
      | principal         | 1000000000                                 |
      | interest_rate_bps | 1000                                       |
      | term_days         | 90                                         |
      | installments      | 3                                          |
      | total_due         | null                                       |
      | total_paid        | 0                                          |
      | installments_paid | 0                                          |
      | funded_at         | null                                       |
      | final_due_date    | null                                       |
      | next_due_date     | null                                       |
    And the loan 7 was requested at the timestamp of block 12
    And the last listener batch lists the borrower among the affected borrowers

  @api-03
  Scenario: A funded loan becomes active with its schedule
    Given the node has 30 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 10
    And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1807776000 is emitted at block 20
    When the indexer runs once
    Then the loan 7 has these values:
      | status            | ACTIVE                                     |
      | lender            | 0x2222222222222222222222222222222222222222 |
      | total_due         | 1024657534                                 |
      | total_paid        | 0                                          |
      | installments_paid | 0                                          |
      | funded_at         | 1800000000                                 |
      | final_due_date    | 1807776000                                 |
      | next_due_date     | 1802592000                                 |

  @api-03
  Scenario: Installments move the next due date
    Given the node has 30 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 10
    And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1807776000 is emitted at block 20
    And a LoanRepaid log for loan 7 of 100 units is emitted at block 25
    When the indexer runs once
    Then the loan 7 has these values:
      | status            | ACTIVE     |
      | total_paid        | 100        |
      | installments_paid | 0          |
      | next_due_date     | 1802592000 |
    When a LoanRepaid log for loan 7 of 341552411 units is emitted at block 40
    And the indexer runs once
    Then the loan 7 has these values:
      | status            | ACTIVE     |
      | total_paid        | 341552511  |
      | installments_paid | 1          |
      | next_due_date     | 1805184000 |
    When a LoanRepaid log for loan 7 of 341552511 units is emitted at block 55
    And the indexer runs once
    Then the loan 7 has these values:
      | status            | ACTIVE     |
      | total_paid        | 683105022  |
      | installments_paid | 2          |
      | next_due_date     | 1807776000 |
    When a LoanRepaid log for loan 7 of 341552512 units is emitted at block 70
    And the indexer runs once
    Then the loan 7 has these values:
      | status            | REPAID     |
      | total_paid        | 1024657534 |
      | installments_paid | 3          |
      | next_due_date     | null       |

  @api-03
  Scenario: A single payment loan is repaid by one payment
    Given the node has 40 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 30 days in 1 installments is emitted at block 10
    And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1802592000 is emitted at block 20
    And a LoanRepaid log for loan 7 of 1008219178 units is emitted at block 30
    When the indexer runs once
    Then the loan 7 has these values:
      | status            | REPAID     |
      | total_due         | 1008219178 |
      | total_paid        | 1008219178 |
      | installments_paid | 1          |
      | next_due_date     | null       |

  @api-03
  Scenario: A dust loan completes on its only payment
    Given the node has 40 blocks
    And a LoanRequested log for loan 7 of 1 units at 1000 basis points over 90 days in 6 installments is emitted at block 10
    And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1807776000 is emitted at block 20
    When the indexer runs once
    Then the loan 7 has these values:
      | status            | ACTIVE     |
      | total_due         | 1          |
      | installments_paid | 0          |
      | next_due_date     | 1807776000 |
    When a LoanRepaid log for loan 7 of 1 units is emitted at block 55
    And the indexer runs once
    Then the loan 7 has these values:
      | status            | REPAID |
      | total_paid        | 1      |
      | installments_paid | 6      |
      | next_due_date     | null   |

  @api-03
  Scenario: A defaulted loan is final
    Given the node has 50 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 10
    And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1807776000 is emitted at block 20
    And a LoanDefaulted log for loan 7 is emitted at block 30
    And a LoanRepaid log for loan 7 of 341552511 units is emitted at block 40
    When the indexer runs once
    Then the loan 7 has these values:
      | status            | DEFAULTED |
      | total_paid        | 0         |
      | installments_paid | 0         |
      | next_due_date     | null      |

  @api-03 @review-wr-02
  Scenario: An open request stays OPEN however long it waits
    Given the node has 10 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 5
    When the indexer runs once
    And the node mines to block 20005
    And the indexer runs once
    Then the cycle ran to the head
    And the loan 7 has these values:
      | status | OPEN |
      | lender | null |

  @api-03
  Scenario: A reorged request removes the loan
    Given the node has 20 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 15
    When the indexer runs once
    Then the loan table has 1 row
    When the node reorgs from block 14
    And the indexer runs once
    Then there is no loan row for loan 7
    And the loan table has 0 rows
    And the last listener batch lists the borrower among the affected borrowers

  @api-03
  Scenario: A reorg that removes the funding reopens the loan
    Given the node has 20 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 10
    And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1807776000 is emitted at block 15
    When the indexer runs once
    Then the loan 7 has these values:
      | status | ACTIVE |
    When the node reorgs from block 14
    And the indexer runs once
    Then the loan 7 has these values:
      | status            | OPEN |
      | lender            | null |
      | total_due         | null |
      | total_paid        | 0    |
      | installments_paid | 0    |
      | funded_at         | null |
      | final_due_date    | null |
      | next_due_date     | null |

  @api-03
  Scenario: Running the projection again changes nothing
    Given the node has 40 blocks
    And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 10
    And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1807776000 is emitted at block 20
    And a LoanRepaid log for loan 7 of 341552511 units is emitted at block 30
    When the indexer runs once
    And the loan rows are noted
    And the indexer runs once
    And the loan projection runs again for the loans "7"
    Then the loan table has 1 row
    And the loan rows are the same as noted

  @api-03
  Scenario: Loans do not disturb each other
    Given the node has 60 blocks
    And a LoanRequested log for loan 1 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 10
    And a LoanRequested log for loan 2 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 11
    And a LoanRequested log for loan 3 of 1 units at 1000 basis points over 30 days in 1 installments is emitted at block 12
    And a LoanFunded log for loan 2 at the funding time 1800000000 with the due date 1807776000 is emitted at block 20
    And a LoanFunded log for loan 3 at the funding time 1800000000 with the due date 1802592000 is emitted at block 21
    And a LoanRepaid log for loan 2 of 341552511 units is emitted at block 30
    And a LoanRepaid log for loan 3 of 1 units is emitted at block 31
    When the indexer runs once
    Then the loan table has 3 rows
    And the loan 1 has these values:
      | status        | OPEN |
      | total_paid    | 0    |
      | next_due_date | null |
    And the loan 2 has these values:
      | status            | ACTIVE     |
      | total_paid        | 341552511  |
      | installments_paid | 1          |
      | next_due_date     | 1805184000 |
    And the loan 3 has these values:
      | status            | REPAID |
      | total_due         | 1      |
      | installments_paid | 1      |
      | next_due_date     | null   |
