package com.latifi.api.reputation.domain;

import java.util.Collection;
import java.util.Comparator;

/** Deterministic fold of signals into a ledger and a result. */
public final class ScoreLedger {

  /** Total order of the fold. */
  public static final Comparator<ReputationSignal> TOTAL_ORDER =
      Comparator.comparing(ReputationSignal::occurredAt);

  private ScoreLedger() {}

  public static ReputationResult fold(
      ReputationPolicy policy, Collection<ReputationSignal> signals) {
    throw new UnsupportedOperationException();
  }
}
