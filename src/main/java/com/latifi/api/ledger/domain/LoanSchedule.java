package com.latifi.api.ledger.domain;

import java.math.BigInteger;

/**
 * Java replica of the schedule arithmetic of the LoanBook contract. The indexer context uses it to
 * tell which installment a payment applies to and when that installment fell due.
 */
public record LoanSchedule(
    BigInteger principal, int rateBps, long termDays, int installments, long fundedAt) {

  public BigInteger totalDue() {
    throw new UnsupportedOperationException();
  }

  public BigInteger baseInstallment() {
    throw new UnsupportedOperationException();
  }

  public int currentIndex(BigInteger paid) {
    throw new UnsupportedOperationException();
  }

  public BigInteger target(int index) {
    throw new UnsupportedOperationException();
  }

  public BigInteger remaining(BigInteger paid) {
    throw new UnsupportedOperationException();
  }

  public long dueAt(int index) {
    throw new UnsupportedOperationException();
  }

  public long nextDueAt(BigInteger paid) {
    throw new UnsupportedOperationException();
  }

  public boolean completesInstallment(BigInteger paidBefore, BigInteger amount) {
    throw new UnsupportedOperationException();
  }

  public int installmentsPaid(BigInteger paid) {
    throw new UnsupportedOperationException();
  }

  public static int daysLate(long blockTimestamp, long dueAt) {
    throw new UnsupportedOperationException();
  }
}
