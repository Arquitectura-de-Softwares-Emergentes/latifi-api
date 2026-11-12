package com.latifi.api.reputation.domain;

import java.util.List;

/**
 * The outcome of a fold: the score, its level, the counters and the ledger.
 *
 * @param onTimeCount entries of type {@code REPAID_ON_TIME}
 * @param lateCount entries of type {@code REPAID_LATE}
 * @param defaultCount entries of type {@code DEFAULTED}
 * @param profileComplete true when a {@code PROFILE_COMPLETED} entry exists
 */
public record ReputationResult(
    int score,
    ReputationLevel level,
    int onTimeCount,
    int lateCount,
    int defaultCount,
    boolean profileComplete,
    List<LedgerEntry> entries) {}
