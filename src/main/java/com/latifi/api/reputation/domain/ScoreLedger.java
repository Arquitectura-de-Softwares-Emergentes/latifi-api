package com.latifi.api.reputation.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic fold of the signals of one address into a ledger and a result.
 *
 * <p>The fold is a pure function of the policy and the signals. It reads no clock, it does not
 * depend on the order of its input, and a repeated source key counts once. After every entry the
 * score is clamped to 0 through 1000, and the ledger stores the effective delta, so {@code
 * scoreAfter = scoreBefore + delta} holds for every entry. The rules are written down in {@code
 * docs/reputation-ddd.md}.
 */
public final class ScoreLedger {

  /**
   * Total order of the fold: timestamp, then source rank (profile first), then block number, then
   * log index, then source key. The last key makes the order total, so equal inputs give equal
   * ledgers whatever the order they arrive in.
   */
  public static final Comparator<ReputationSignal> TOTAL_ORDER =
      Comparator.comparing(ReputationSignal::occurredAt)
          .thenComparingInt(ReputationSignal::sourceRank)
          .thenComparingLong(ReputationSignal::blockNumber)
          .thenComparingInt(ReputationSignal::logIndex)
          .thenComparing(ReputationSignal::sourceKey);

  private ScoreLedger() {}

  public static ReputationResult fold(
      ReputationPolicy policy, Collection<ReputationSignal> signals) {
    int score = policy.startScore();
    int onTime = 0;
    int late = 0;
    int defaults = 0;
    boolean profileComplete = false;
    boolean chainSignal = false;
    List<LedgerEntry> entries = new ArrayList<>();

    for (ReputationSignal signal : uniqueInOrder(signals)) {
      Long raw = rawDelta(policy, signal);
      if (raw == null) {
        continue;
      }
      int scoreAfter = clamp(score + raw);
      entries.add(new LedgerEntry(entries.size() + 1, signal, scoreAfter - score, scoreAfter));
      score = scoreAfter;
      switch (signal.type()) {
        case PROFILE_COMPLETED -> profileComplete = true;
        case REPAID_ON_TIME -> {
          onTime++;
          chainSignal = true;
        }
        case REPAID_LATE -> {
          late++;
          chainSignal = true;
        }
        case DEFAULTED -> {
          defaults++;
          chainSignal = true;
        }
      }
    }

    ReputationLevel level = chainSignal ? ReputationLevel.ofScore(score) : ReputationLevel.NEW;
    return new ReputationResult(
        score, level, onTime, late, defaults, profileComplete, List.copyOf(entries));
  }

  /** Sorts by the total order and keeps the first signal of each source key. */
  private static Collection<ReputationSignal> uniqueInOrder(Collection<ReputationSignal> signals) {
    List<ReputationSignal> sorted = new ArrayList<>(signals);
    sorted.sort(TOTAL_ORDER);
    Map<String, ReputationSignal> unique = new LinkedHashMap<>();
    for (ReputationSignal signal : sorted) {
      unique.putIfAbsent(signal.sourceKey(), signal);
    }
    return unique.values();
  }

  /** The delta of the policy before the clamp, or null when the signal creates no entry. */
  private static Long rawDelta(ReputationPolicy policy, ReputationSignal signal) {
    return switch (signal.type()) {
      case PROFILE_COMPLETED -> (long) policy.profileBonus();
      case REPAID_ON_TIME -> isBelowMinimum(policy, signal) ? null : (long) policy.onTimeDelta();
      case REPAID_LATE ->
          -Math.min((long) policy.latePerDay() * Math.max(1, signal.daysLate()), policy.lateCap());
      case DEFAULTED -> -(long) policy.defaultDelta();
    };
  }

  /** A positive signal of a loan below the minimum scored principal creates no entry. */
  private static boolean isBelowMinimum(ReputationPolicy policy, ReputationSignal signal) {
    return signal.principal() != null
        && signal.principal().compareTo(policy.minScoredPrincipal()) < 0;
  }

  private static int clamp(long value) {
    return (int) Math.max(ReputationPolicy.MIN_SCORE, Math.min(ReputationPolicy.MAX_SCORE, value));
  }
}
