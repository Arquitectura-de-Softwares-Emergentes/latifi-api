package com.latifi.api.reputation.domain;

import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.LoanState;
import java.util.List;

/**
 * One loan of a borrower with the events that can produce a signal.
 *
 * @param loan the current state of the loan, read from the loan table
 * @param events the LoanRepaid and LoanDefaulted events of that loan, in chain order
 */
public record BorrowerLoanHistory(LoanState loan, List<ChainEvent> events) {

  public BorrowerLoanHistory {
    events = List.copyOf(events);
  }
}
