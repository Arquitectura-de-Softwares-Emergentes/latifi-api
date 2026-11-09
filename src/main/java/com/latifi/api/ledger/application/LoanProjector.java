package com.latifi.api.ledger.application;

import com.latifi.api.ledger.domain.BatchDelta;
import com.latifi.api.ledger.domain.ChainEventListener;
import com.latifi.api.ledger.domain.ChainEventRepository;
import com.latifi.api.ledger.domain.LoanFolder;
import com.latifi.api.ledger.domain.LoanRepository;
import com.latifi.api.ledger.domain.LoanState;
import java.math.BigInteger;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@code loan} table equal to what the stored events say. For every loan that a batch
 * touched, it loads the loan's events, folds them and writes the result, or deletes the row when no
 * LoanRequested remains. A loan that the batch touched twice, or that a reorg removed and another
 * block brought back, ends up right either way, because nothing depends on the previous row.
 *
 * <p>It runs inside the transaction of the batch, so the loan rows commit together with the events.
 * It runs before the reputation projector ({@code @Order(20)}) and hands it the borrowers whose
 * loans changed, both the borrower that the row had before and the one it has now.
 */
@Component
@Order(10)
public class LoanProjector implements ChainEventListener {

  private final ChainEventRepository events;
  private final LoanRepository loans;

  public LoanProjector(ChainEventRepository events, LoanRepository loans) {
    this.events = events;
    this.loans = loans;
  }

  @Override
  public void onBatchApplied(BatchDelta delta) {
    // Sorted, so two runs over the same batch touch the rows in the same order.
    for (BigInteger loanId : new TreeSet<>(delta.touchedLoanIds())) {
      loans.find(loanId).map(LoanState::borrower).ifPresent(delta::addAffectedBorrower);
      Optional<LoanState> state = LoanFolder.fold(events.findByLoanId(loanId));
      if (state.isPresent()) {
        loans.upsert(state.get());
        delta.addAffectedBorrower(state.get().borrower());
      } else {
        loans.delete(loanId);
      }
    }
  }
}
