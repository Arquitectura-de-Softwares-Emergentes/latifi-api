package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.util.Set;

/**
 * What one applied batch changed.
 *
 * @param touchedLoanIds the loans that got at least one newly inserted event
 */
public record BatchDelta(Set<BigInteger> touchedLoanIds) {

  public BatchDelta {
    touchedLoanIds = Set.copyOf(touchedLoanIds);
  }
}
