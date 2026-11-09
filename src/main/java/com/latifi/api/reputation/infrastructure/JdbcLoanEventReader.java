package com.latifi.api.reputation.infrastructure;

import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventRepository;
import com.latifi.api.ledger.domain.ChainEventType;
import com.latifi.api.ledger.domain.LoanRepository;
import com.latifi.api.reputation.domain.BorrowerLoanHistory;
import com.latifi.api.reputation.domain.LoanEventReader;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Reads the loans of a borrower from the loan table and the events of each loan from the event log,
 * through the read side of the ledger context. It keeps only the events that can produce a signal.
 */
@Repository
public class JdbcLoanEventReader implements LoanEventReader {

  private final LoanRepository loans;
  private final ChainEventRepository events;

  public JdbcLoanEventReader(LoanRepository loans, ChainEventRepository events) {
    this.loans = loans;
    this.events = events;
  }

  @Override
  public List<BorrowerLoanHistory> historiesOf(String borrower) {
    return loans.findByBorrower(borrower).stream()
        .map(
            loan ->
                new BorrowerLoanHistory(
                    loan,
                    events.findByLoanId(loan.loanId()).stream()
                        .filter(JdbcLoanEventReader::producesSignal)
                        .toList()))
        .toList();
  }

  private static boolean producesSignal(ChainEvent event) {
    return event.type() == ChainEventType.LOAN_REPAID
        || event.type() == ChainEventType.LOAN_DEFAULTED;
  }
}
