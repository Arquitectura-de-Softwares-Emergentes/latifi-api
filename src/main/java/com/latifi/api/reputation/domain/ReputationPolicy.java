package com.latifi.api.reputation.domain;

import java.math.BigInteger;

/**
 * The parameters that turn a signal into a delta. The values live in configuration. The range of
 * the score is fixed here.
 *
 * @param startScore score of an address before any entry
 * @param onTimeDelta points added for an installment completed on time
 * @param latePerDay points removed for each day of delay
 * @param lateCap largest total penalty of one late installment
 * @param defaultDelta points removed for a default
 * @param profileBonus points added once, when the profile is complete
 * @param minScoredPrincipal smallest principal, in base units, whose on-time installments score
 */
public record ReputationPolicy(
    int startScore,
    int onTimeDelta,
    int latePerDay,
    int lateCap,
    int defaultDelta,
    int profileBonus,
    BigInteger minScoredPrincipal) {

  public static final int MIN_SCORE = 0;
  public static final int MAX_SCORE = 1000;

  public ReputationPolicy {
    if (startScore < MIN_SCORE || startScore > MAX_SCORE) {
      throw new IllegalArgumentException(
          "start-score must be between " + MIN_SCORE + " and " + MAX_SCORE + ": " + startScore);
    }
    requireNotNegative("on-time-delta", onTimeDelta);
    requireNotNegative("late-per-day", latePerDay);
    requireNotNegative("late-cap", lateCap);
    requireNotNegative("default-delta", defaultDelta);
    requireNotNegative("profile-bonus", profileBonus);
    if (minScoredPrincipal == null || minScoredPrincipal.signum() < 0) {
      throw new IllegalArgumentException("min-scored-principal must not be negative");
    }
  }

  private static void requireNotNegative(String name, int value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative: " + value);
    }
  }
}
