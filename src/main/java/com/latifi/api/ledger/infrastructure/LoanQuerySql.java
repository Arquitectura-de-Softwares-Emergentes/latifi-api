package com.latifi.api.ledger.infrastructure;

/**
 * The SQL text of the loan read side. The text comes only from fixed literals. A borrower without a
 * row in {@code reputation_state} counts as score 500, level NEW and level rank 0.
 */
final class LoanQuerySql {

  /** Every column of a loan plus the reputation of its borrower, with the defaults applied. */
  static final String SELECT =
      """
      select l.loan_id, l.borrower, l.lender, l.principal, l.interest_rate_bps, l.term_days,
             l.installments, l.status, l.total_due, l.total_paid, l.installments_paid,
             l.requested_at, l.funded_at, l.final_due_date, l.next_due_date,
             coalesce(rs.score, 500) as borrower_score,
             coalesce(rs.level, 'NEW') as borrower_level
      from loan l
      left join reputation_state rs on rs.address = l.borrower
      """;

  private LoanQuerySql() {}
}
