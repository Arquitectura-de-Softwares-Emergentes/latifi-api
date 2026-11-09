package com.latifi.api.ledger.domain;

/**
 * A loan together with the reputation of its borrower, as the feed and the detail show it. A
 * borrower without a reputation row has score 500 and level NEW.
 *
 * @param borrowerLevel one of NEW, BUILDING, TRUSTED and EXCELLENT
 */
public record LoanView(LoanState loan, int borrowerScore, String borrowerLevel) {

  /** Score of a borrower that has no reputation row. */
  public static final int DEFAULT_SCORE = 500;

  /** Level of a borrower that has no reputation row. */
  public static final String DEFAULT_LEVEL = "NEW";

  /** The same loan with another stored total due, used to project the total of an open request. */
  public LoanView withLoan(LoanState other) {
    return new LoanView(other, borrowerScore, borrowerLevel);
  }
}
