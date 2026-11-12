package com.latifi.api.ledger.domain;

/**
 * Outcome of one indexer cycle.
 *
 * @param blocksScanned blocks asked from the node, counting the re-read window
 * @param eventsInserted rows that were new
 * @param eventsRemoved rows deleted because a reorg took their log off the chain
 * @param lastBlock the checkpoint after the cycle
 * @param skippedReason why the cycle stopped without finishing, or {@code null} when it ran to the
 *     head. The counters then hold what the batches before the skip already committed.
 */
public record CycleResult(
    long blocksScanned,
    int eventsInserted,
    int eventsRemoved,
    long lastBlock,
    String skippedReason) {

  /** Another instance holds the advisory lock. */
  public static final String LOCK_HELD = "lock-held";

  /** The node reports a block below the checkpoint. */
  public static final String NODE_BEHIND = "node-behind";

  /** The node reports another chain than the configured one. */
  public static final String CHAIN_ID_MISMATCH = "chain-id-mismatch";

  /** The start block is not mined yet, so there is nothing to read. */
  public static final String UP_TO_DATE = "up-to-date";

  public boolean skipped() {
    return skippedReason != null;
  }
}
