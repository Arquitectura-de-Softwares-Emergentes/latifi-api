package com.latifi.api.ledger.domain;

import java.math.BigInteger;

/**
 * Java replica of the schedule arithmetic of the LoanBook contract. The indexer context uses it to
 * tell which installment a payment applies to and when that installment fell due.
 *
 * <p>Every amount is a {@link BigInteger}, because the contract stores uint256 values, and every
 * division is an integer division, as in Solidity. The class reads no clock and uses no decimal
 * arithmetic, so the same loan always gives the same schedule.
 *
 * <p>The rules, copied from the contract:
 *
 * <ul>
 *   <li>{@code totalDue = principal + floor(principal * rateBps * termDays / 3650000)}
 *   <li>{@code base = floor(totalDue / installments)}. Installments 1 to N minus 1 equal {@code
 *       base}, and the last one takes the remainder.
 *   <li>Installment k falls due at {@code fundedAt + k * termDays * 86400 / installments}.
 *   <li>When {@code base} is zero (a total smaller than the number of installments), everything
 *       sits on the last installment.
 * </ul>
 */
public record LoanSchedule(
    BigInteger principal, int rateBps, long termDays, int installments, long fundedAt) {

  private static final BigInteger INTEREST_DIVISOR = BigInteger.valueOf(3650000L);
  private static final long SECONDS_PER_DAY = 86_400L;

  public LoanSchedule {
    if (principal == null || principal.signum() < 0) {
      throw new IllegalArgumentException("principal must not be negative");
    }
    if (installments < 1) {
      throw new IllegalArgumentException("installments must be at least 1");
    }
    if (termDays < 1) {
      throw new IllegalArgumentException("termDays must be at least 1");
    }
  }

  /** Principal plus simple annual interest, rounded down, as the contract computes it. */
  public BigInteger totalDue() {
    BigInteger interest =
        principal
            .multiply(BigInteger.valueOf(rateBps))
            .multiply(BigInteger.valueOf(termDays))
            .divide(INTEREST_DIVISOR);
    return principal.add(interest);
  }

  /** The size of installments 1 to N minus 1. It is zero in the dust case. */
  public BigInteger baseInstallment() {
    return totalDue().divide(BigInteger.valueOf(installments));
  }

  /**
   * The installment that the next payment applies to, from 1 to N, given the cumulative amount paid
   * so far.
   */
  public int currentIndex(BigInteger paid) {
    BigInteger base = baseInstallment();
    if (base.signum() == 0) {
      return installments;
    }
    BigInteger index = paid.divide(base).add(BigInteger.ONE);
    BigInteger last = BigInteger.valueOf(installments);
    return index.compareTo(last) > 0 ? installments : index.intValueExact();
  }

  /** The cumulative amount that leaves installment {@code index} exactly paid. */
  public BigInteger target(int index) {
    requireInRange(index);
    if (index == installments) {
      return totalDue();
    }
    return baseInstallment().multiply(BigInteger.valueOf(index));
  }

  /** What is still owed on the current installment before the next payment. */
  public BigInteger remaining(BigInteger paid) {
    return target(currentIndex(paid)).subtract(paid);
  }

  /** Unix seconds at which installment {@code index} falls due. */
  public long dueAt(int index) {
    requireInRange(index);
    long offset =
        Math.multiplyExact(Math.multiplyExact((long) index, termDays), SECONDS_PER_DAY)
            / installments;
    return Math.addExact(fundedAt, offset);
  }

  /** Due time of the current installment. */
  public long nextDueAt(BigInteger paid) {
    return dueAt(currentIndex(paid));
  }

  /**
   * True when a payment of {@code amount} leaves the cumulative paid amount exactly on the target
   * of the current installment. A smaller payment is partial, and a larger one is an overpayment
   * that the contract rejects.
   */
  public boolean completesInstallment(BigInteger paidBefore, BigInteger amount) {
    return amount.signum() > 0 && amount.equals(remaining(paidBefore));
  }

  /** Installments fully paid for a cumulative amount, from 0 to N. */
  public int installmentsPaid(BigInteger paid) {
    if (paid.compareTo(totalDue()) >= 0) {
      return installments;
    }
    BigInteger base = baseInstallment();
    if (base.signum() == 0) {
      return 0;
    }
    BigInteger done = paid.divide(base);
    BigInteger cap = BigInteger.valueOf(installments - 1L);
    return done.compareTo(cap) > 0 ? installments - 1 : done.intValueExact();
  }

  /**
   * Whole days of delay for a payment that the event flagged as late. A payment late by one second
   * counts as one day. A timestamp that does not exceed the due time also counts as one day,
   * because the flag of the event decides and the minimum is one day.
   */
  public static int daysLate(long blockTimestamp, long dueAt) {
    long days = Math.ceilDiv(blockTimestamp - dueAt, SECONDS_PER_DAY);
    return (int) Math.max(1L, days);
  }

  private void requireInRange(int index) {
    if (index < 1 || index > installments) {
      throw new IllegalArgumentException("installment index out of range: " + index);
    }
  }
}
