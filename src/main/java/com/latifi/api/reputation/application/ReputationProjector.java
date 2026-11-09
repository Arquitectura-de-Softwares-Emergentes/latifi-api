package com.latifi.api.reputation.application;

import com.latifi.api.identity.domain.ProfileSaved;
import com.latifi.api.ledger.domain.BatchDelta;
import com.latifi.api.ledger.domain.ChainEventListener;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.reputation.domain.BorrowerLoanHistory;
import com.latifi.api.reputation.domain.LedgerEntry;
import com.latifi.api.reputation.domain.LoanEventReader;
import com.latifi.api.reputation.domain.ProfileSignalSource;
import com.latifi.api.reputation.domain.ReputationPolicy;
import com.latifi.api.reputation.domain.ReputationRepository;
import com.latifi.api.reputation.domain.ReputationResult;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.ScoreLedger;
import com.latifi.api.reputation.domain.SignalBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the reputation tables equal to what the indexed events and the profile say. Every recompute
 * is complete: it loads the loans, the events and the profile of one address, builds the signals,
 * folds them and writes the whole result. Nothing depends on a previous score, so a reorg, a
 * repeated batch or a profile save in between cannot leave a wrong number behind.
 *
 * <p>It runs inside the transaction of the caller. As a {@link ChainEventListener} it runs after
 * the loan projector ({@code @Order(10)}) and recomputes every borrower that projector reported. As
 * an event listener of {@link ProfileSaved} it recomputes the saved address in the transaction of
 * the profile write. It reads no clock.
 */
@Component
@Order(20)
public class ReputationProjector implements ChainEventListener {

  private final ReputationPolicy policy;
  private final LoanEventReader loans;
  private final ProfileSignalSource profiles;
  private final ReputationRepository reputation;

  public ReputationProjector(
      ReputationPolicy policy,
      LoanEventReader loans,
      ProfileSignalSource profiles,
      ReputationRepository reputation) {
    this.policy = policy;
    this.loans = loans;
    this.profiles = profiles;
    this.reputation = reputation;
  }

  @Override
  @Transactional
  public void onBatchApplied(BatchDelta delta) {
    // Sorted, so two runs over the same batch write the addresses in the same order.
    for (String borrower : new TreeSet<>(delta.affectedBorrowers())) {
      recompute(borrower);
    }
  }

  @EventListener
  @Transactional
  public void onProfileSaved(ProfileSaved event) {
    recompute(event.address());
  }

  /**
   * Recomputes one address (lowercase) in the running transaction. An address with neither a
   * profile nor a loan loses its ledger and its state.
   */
  @Transactional
  public void recompute(String address) {
    reputation.lock(address);
    Optional<Instant> profileCreatedAt = profiles.profileCreatedAt(address);
    List<BorrowerLoanHistory> histories = loans.historiesOf(address);
    if (profileCreatedAt.isEmpty() && histories.isEmpty()) {
      reputation.delete(address);
      return;
    }
    List<ReputationSignal> signals = SignalBuilder.build(profileCreatedAt, histories);
    ReputationResult result = ScoreLedger.fold(policy, signals);
    reputation.replace(address, result, updatedAt(result, profileCreatedAt, histories));
  }

  /**
   * The time of the last ledger entry, else the profile creation time, else the earliest request
   * time among the loans. None of them depends on when the recompute runs.
   */
  private static Instant updatedAt(
      ReputationResult result,
      Optional<Instant> profileCreatedAt,
      List<BorrowerLoanHistory> loans) {
    List<LedgerEntry> entries = result.entries();
    if (!entries.isEmpty()) {
      return entries.get(entries.size() - 1).signal().occurredAt();
    }
    if (profileCreatedAt.isPresent()) {
      return profileCreatedAt.get();
    }
    return loans.stream()
        .map(BorrowerLoanHistory::loan)
        .map(LoanState::requestedAt)
        .min(Instant::compareTo)
        .orElseThrow();
  }
}
