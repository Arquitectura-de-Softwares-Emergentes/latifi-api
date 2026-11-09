package com.latifi.api.reputation.domain;

import java.math.BigInteger;

/**
 * The parameters that turn a signal into a delta. The values live in configuration. The range of
 * the score is fixed here.
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
}
