package com.latifi.api.reputation;

import com.latifi.api.reputation.domain.ReputationPolicy;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.SignalType;
import java.math.BigInteger;
import java.time.Instant;

/** Builders shared by the reputation tests. */
final class ReputationFixtures {

  static final BigInteger BIG_PRINCIPAL = BigInteger.valueOf(10_000_000L);
  static final BigInteger MIN_PRINCIPAL = BigInteger.valueOf(1_000_000L);
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  private ReputationFixtures() {}

  /** The policy of docs/reputation-ddd.md. */
  static ReputationPolicy defaultPolicy() {
    return withStart(500);
  }

  static ReputationPolicy withStart(int startScore) {
    return new ReputationPolicy(startScore, 20, 5, 100, 200, 50, MIN_PRINCIPAL);
  }

  static ReputationSignal profile(int second) {
    return new ReputationSignal(
        SignalType.PROFILE_COMPLETED,
        BASE.plusSeconds(second),
        0,
        0,
        null,
        null,
        0,
        null,
        "profile");
  }

  static ReputationSignal onTime(int second, BigInteger principal) {
    return chain(SignalType.REPAID_ON_TIME, second, principal, 0);
  }

  static ReputationSignal onTime(int second) {
    return onTime(second, BIG_PRINCIPAL);
  }

  static ReputationSignal late(int second, int daysLate, BigInteger principal) {
    return chain(SignalType.REPAID_LATE, second, principal, daysLate);
  }

  static ReputationSignal late(int second, int daysLate) {
    return late(second, daysLate, BIG_PRINCIPAL);
  }

  static ReputationSignal defaulted(int second, BigInteger principal) {
    return chain(SignalType.DEFAULTED, second, principal, 0);
  }

  static ReputationSignal defaulted(int second) {
    return defaulted(second, BIG_PRINCIPAL);
  }

  static ReputationSignal chain(SignalType type, int second, BigInteger principal, int daysLate) {
    String hash = "0x" + String.format("%064x", second);
    return new ReputationSignal(
        type,
        BASE.plusSeconds(second),
        1000L + second,
        0,
        "loan-" + second,
        principal,
        daysLate,
        hash,
        "tx:" + hash + ":0");
  }

  static Instant at(int second) {
    return BASE.plusSeconds(second);
  }
}
