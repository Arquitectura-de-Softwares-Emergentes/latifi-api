package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What one applied batch changed. The indexer creates it, and the listeners see it in their order.
 * An early listener can add to {@link #affectedBorrowers()} so that a later one knows whose data to
 * compute again.
 */
public final class BatchDelta {

  private final Set<BigInteger> touchedLoanIds;
  private final Set<String> affectedBorrowers = new HashSet<>();

  /**
   * @param touchedLoanIds the loans that got at least one newly inserted event, plus the loans of
   *     rows that a reorg removed, so a projection knows which loans to compute again
   */
  public BatchDelta(Set<BigInteger> touchedLoanIds) {
    this.touchedLoanIds = Set.copyOf(touchedLoanIds);
  }

  public Set<BigInteger> touchedLoanIds() {
    return touchedLoanIds;
  }

  /**
   * The borrowers whose loans changed in this batch, as lowercase addresses. A read only view of a
   * set that grows while the listeners run. Use {@link #addAffectedBorrower} to add one.
   */
  public Set<String> affectedBorrowers() {
    return Collections.unmodifiableSet(affectedBorrowers);
  }

  /** Marks a borrower as affected. The address is stored in lowercase. */
  public void addAffectedBorrower(String address) {
    affectedBorrowers.add(address.toLowerCase(Locale.ROOT));
  }
}
