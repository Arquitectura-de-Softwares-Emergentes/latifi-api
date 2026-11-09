package com.latifi.api.reputation.domain;

import java.math.BigInteger;
import java.time.Instant;

/**
 * One fact that can move the score of an address. The projector builds signals from the event log
 * and the profile, and {@link ScoreLedger} folds them.
 *
 * @param type what happened
 * @param occurredAt the profile timestamp or the timestamp of the block that holds the event
 * @param blockNumber block of the event, 0 for the profile
 * @param logIndex position of the log in its block, 0 for the profile
 * @param loanId loan the signal belongs to, null for the profile
 * @param principal principal of that loan in base units, null for the profile
 * @param daysLate whole days of delay, 0 when the signal is not late
 * @param txHash transaction of the event, null for the profile
 * @param sourceKey {@code tx:<hash>:<logIndex>} for a chain event, {@code profile} for the profile
 */
public record ReputationSignal(
    SignalType type,
    Instant occurredAt,
    long blockNumber,
    int logIndex,
    String loanId,
    BigInteger principal,
    int daysLate,
    String txHash,
    String sourceKey) {

  /** Rank that orders the profile before chain events that share a timestamp. */
  public int sourceRank() {
    throw new UnsupportedOperationException();
  }
}
