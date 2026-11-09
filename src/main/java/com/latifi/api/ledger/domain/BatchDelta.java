package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.util.Set;

/**
 * What one applied batch changed.
 *
 * @param touchedLoanIds the loans that got at least one newly inserted event, plus the loans of
 *     rows that a reorg removed, so a projection knows which loans to compute again
 */
public record BatchDelta(Set<BigInteger> touchedLoanIds) {

  public BatchDelta {
    touchedLoanIds = Set.copyOf(touchedLoanIds);
  }
}
