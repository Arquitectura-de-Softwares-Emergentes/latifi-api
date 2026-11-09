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
