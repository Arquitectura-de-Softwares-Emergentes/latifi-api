package com.latifi.api.reputation.domain;

import java.util.List;

/** Port to the loans of a borrower and their repayment and default events. */
public interface LoanEventReader {

  /**
   * The loans whose borrower is {@code borrower} (lowercase address), each with its LoanRepaid and
   * LoanDefaulted events in chain order. An address with no loan gets an empty list.
   */
  List<BorrowerLoanHistory> historiesOf(String borrower);
}
