package com.latifi.api.ledger.domain;

/**
 * Outcome of one indexer cycle.
 *
 * @param blocksScanned blocks asked from the node, counting the re-read window
 * @param eventsInserted rows that were new
 * @param lastBlock the checkpoint after the cycle
 * @param skippedReason why the cycle did nothing, or {@code null} when it ran
 */
public record CycleResult(
    long blocksScanned, int eventsInserted, long lastBlock, String skippedReason) {

  public boolean skipped() {
    return skippedReason != null;
  }
}
