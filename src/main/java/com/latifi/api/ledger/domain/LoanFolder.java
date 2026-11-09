package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Folds the events of one loan into its current state. The fold is a pure function: the same events
 * always give the same state, it reads no clock, and it never touches the database. That is what
 * lets the projection repair itself after a reorg, because it only has to run again over the events
 * that remain.
 *
 * <p>The rules, applied in {@code (blockNumber, logIndex)} order:
 *
 * <ul>
 *   <li>Without a LoanRequested there is no loan. A second LoanRequested is ignored.
 *   <li>LoanRequested opens the loan with its terms and the block time as {@code requestedAt}.
 *   <li>LoanFunded on an OPEN loan sets the lender, the times the contract reported, the total due
 *       from {@link LoanSchedule} and makes the loan ACTIVE.
 *   <li>LoanRepaid on an ACTIVE loan adds the payment. When the payments reach the total due the
 *       loan is REPAID.
 *   <li>LoanDefaulted on an ACTIVE loan makes it DEFAULTED, and that is final.
 *   <li>An event that does not fit the current status is ignored.
 *   <li>While the loan is ACTIVE the next due date is the due time of the installment being paid.
 *       In every other status it is {@code null}.
 * </ul>
 */
public final class LoanFolder {

  private LoanFolder() {}

  private static final Comparator<ChainEvent> CHAIN_ORDER =
      Comparator.comparingLong(ChainEvent::blockNumber).thenComparingInt(ChainEvent::logIndex);

  /**
   * @param events the events of one loan, in any order; the fold sorts them by chain position
   * @return the state, or empty when the loan has no LoanRequested event
   */
  public static Optional<LoanState> fold(List<ChainEvent> events) {
    List<ChainEvent> ordered = events.stream().sorted(CHAIN_ORDER).toList();
    Working loan = null;
    for (ChainEvent event : ordered) {
      switch (event.type()) {
        case LOAN_REQUESTED -> {
          if (loan == null) {
            loan = Working.requested(event);
          }
        }
        case LOAN_FUNDED -> {
          if (loan != null && loan.status == LoanStatus.OPEN) {
            loan.funded(event);
          }
        }
        case LOAN_REPAID -> {
          if (loan != null && loan.status == LoanStatus.ACTIVE) {
            loan.repaid(event);
          }
        }
        case LOAN_DEFAULTED -> {
          if (loan != null && loan.status == LoanStatus.ACTIVE) {
            loan.status = LoanStatus.DEFAULTED;
          }
        }
      }
    }
    return Optional.ofNullable(loan).map(Working::toState);
  }

  /** The loan while the fold runs. It never leaves this class. */
  private static final class Working {
    final BigInteger loanId;
    final String borrower;
    final BigInteger principal;
    final int interestRateBps;
    final int termDays;
    final int installments;
    final Instant requestedAt;

    LoanStatus status = LoanStatus.OPEN;
    String lender;
    Instant fundedAt;
    Instant finalDueDate;
    LoanSchedule schedule;
    BigInteger totalPaid = BigInteger.ZERO;
    int installmentsPaid;

    private Working(ChainEvent event) {
      this.loanId = event.loanId();
      this.borrower = event.payload().get("borrower").toLowerCase(Locale.ROOT);
      this.principal = new BigInteger(event.payload().get("amount"));
      this.interestRateBps = Integer.parseInt(event.payload().get("interestRateBps"));
      this.termDays = Integer.parseInt(event.payload().get("termDays"));
      this.installments = Integer.parseInt(event.payload().get("installments"));
      this.requestedAt = event.blockTimestamp();
    }

    static Working requested(ChainEvent event) {
      return new Working(event);
    }

    void funded(ChainEvent event) {
      long fundedAtSeconds = Long.parseLong(event.payload().get("fundedAt"));
      lender = event.payload().get("lender").toLowerCase(Locale.ROOT);
      fundedAt = Instant.ofEpochSecond(fundedAtSeconds);
      finalDueDate = Instant.ofEpochSecond(Long.parseLong(event.payload().get("dueDate")));
      schedule =
          new LoanSchedule(principal, interestRateBps, termDays, installments, fundedAtSeconds);
      status = LoanStatus.ACTIVE;
    }

    void repaid(ChainEvent event) {
      totalPaid = totalPaid.add(new BigInteger(event.payload().get("amountPaid")));
      installmentsPaid = schedule.installmentsPaid(totalPaid);
      if (totalPaid.compareTo(schedule.totalDue()) >= 0) {
        status = LoanStatus.REPAID;
      }
    }

    LoanState toState() {
      Instant nextDue =
          status == LoanStatus.ACTIVE ? Instant.ofEpochSecond(schedule.nextDueAt(totalPaid)) : null;
      return new LoanState(
          loanId,
          borrower,
          lender,
          principal,
          interestRateBps,
          termDays,
          installments,
          status,
          schedule == null ? null : schedule.totalDue(),
          totalPaid,
          installmentsPaid,
          requestedAt,
          fundedAt,
          finalDueDate,
          nextDue);
    }
  }
}
