package com.latifi.api.reputation.domain;

import java.time.Instant;

/**
 * The stored result of the last recompute of an address.
 *
 * @param address lowercase address
 * @param updatedAt deterministic: the time of the last ledger entry, else the profile creation
 *     time, else the earliest request time among the loans of the address
 */
public record ReputationState(
    String address,
    int score,
    ReputationLevel level,
    int onTimeCount,
    int lateCount,
    int defaultCount,
    boolean profileComplete,
    Instant updatedAt) {}
