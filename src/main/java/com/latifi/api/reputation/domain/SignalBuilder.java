package com.latifi.api.reputation.domain;

import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.LoanSchedule;
import com.latifi.api.ledger.domain.LoanState;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns the loans, the events and the profile of one borrower into reputation signals. It is a pure
 * function with no clock: every time comes from a block or from the profile row.
 *
 * <p>The rule that closes review item WR-01 of Phase 3 sits here. The contract counts payments, so
 * a borrower could split an installment into dust payments and raise the counter. This builder
 * counts completed installments. A payment makes a signal only when it equals what remains of the
 * current installment. Any other amount adds to the paid total and makes no signal.
 *
 * <p>A completed installment is on time when the {@code onTime} flag of the event says so. The flag
 * decides because the contract computed it with the block time that the chain recorded. When the
 * completing payment is late, the installment is late, even if earlier parts came on time.
 */
public final class SignalBuilder {

  private static final Comparator<ChainEvent> CHAIN_ORDER =
      Comparator.comparingLong(ChainEvent::blockNumber).thenComparingInt(ChainEvent::logIndex);

  private SignalBuilder() {}

  /**
   * @param profileCreatedAt creation time of the profile, empty when there is none
   * @param histories the loans of the borrower with their repayment and default events
   */
  public static List<ReputationSignal> build(
      Optional<Instant> profileCreatedAt, List<BorrowerLoanHistory> histories) {
    List<ReputationSignal> signals = new ArrayList<>();
    profileCreatedAt.ifPresent(
        createdAt ->
            signals.add(
                new ReputationSignal(
                    SignalType.PROFILE_COMPLETED,
                    createdAt,
                    0,
                    0,
                    null,
                    null,
                    0,
                    null,
                    "profile")));
    for (BorrowerLoanHistory history : histories) {
      signalsOfLoan(history, signals);
    }
    return List.copyOf(signals);
  }

  private static void signalsOfLoan(BorrowerLoanHistory history, List<ReputationSignal> out) {
    LoanState loan = history.loan();
    if (loan.fundedAt() == null) {
      return;
    }
    LoanSchedule schedule =
        new LoanSchedule(
            loan.principal(),
            loan.interestRateBps(),
            loan.termDays(),
            loan.installments(),
            loan.fundedAt().getEpochSecond());
    BigInteger totalDue = schedule.totalDue();
    BigInteger paid = BigInteger.ZERO;
    for (ChainEvent event : history.events().stream().sorted(CHAIN_ORDER).toList()) {
      switch (event.type()) {
        case LOAN_REPAID -> {
          if (paid.compareTo(totalDue) >= 0) {
            continue;
          }
          BigInteger amount = new BigInteger(event.payload().get("amountPaid"));
          if (schedule.completesInstallment(paid, amount)) {
            long dueAt = schedule.dueAt(schedule.currentIndex(paid));
            boolean onTime = Boolean.parseBoolean(event.payload().get("onTime"));
            out.add(
                onTime
                    ? signal(SignalType.REPAID_ON_TIME, event, loan, 0)
                    : signal(
                        SignalType.REPAID_LATE,
                        event,
                        loan,
                        LoanSchedule.daysLate(event.blockTimestamp().getEpochSecond(), dueAt)));
          }
          paid = paid.add(amount);
        }
        case LOAN_DEFAULTED -> {
          if (paid.compareTo(totalDue) >= 0) {
            // A loan that is already repaid in full cannot default, as in the loan fold.
            continue;
          }
          long dueAt = schedule.dueAt(schedule.currentIndex(paid));
          out.add(
              signal(
                  SignalType.DEFAULTED,
                  event,
                  loan,
                  LoanSchedule.daysLate(event.blockTimestamp().getEpochSecond(), dueAt)));
          // A default is final, so later events of the loan count for nothing.
          return;
        }
        default -> {
          // LoanRequested and LoanFunded are already folded into the loan state.
        }
      }
    }
  }

  private static ReputationSignal signal(
      SignalType type, ChainEvent event, LoanState loan, int daysLate) {
    return new ReputationSignal(
        type,
        event.blockTimestamp(),
        event.blockNumber(),
        event.logIndex(),
        loan.loanId().toString(),
        loan.principal(),
        daysLate,
        event.txHash(),
        "tx:" + event.txHash() + ":" + event.logIndex());
  }
}
