package com.latifi.api.ledger.domain;

import java.util.List;
import java.util.OptionalLong;

/** Storage port of the indexer. */
public interface ChainEventRepository {

  /**
   * Stores the events that are not stored yet.
   *
   * @return only the events that this call really inserted, in the order given
   */
  List<ChainEvent> insertNew(List<ChainEvent> events);

  /**
   * Makes the stored rows of {@code fromBlock..toBlock} agree with what the node returned for the
   * same range. A stored row stays only when its transaction hash, log index and block hash all
   * appear in {@code fresh}. Every other row of the range is deleted, because the chain no longer
   * holds it. A row outside the range goes too when a fresh log has the same transaction hash and
   * log index but another block hash: that transaction was included again in a different block.
   *
   * <p>Call it before {@link #insertNew} and inside the same transaction.
   *
   * @return the rows that this call deleted
   */
  List<ChainEvent> reconcileWindow(long fromBlock, long toBlock, List<ChainEvent> fresh);

  /** The last block that a finished batch covered, or empty before the first batch. */
  OptionalLong checkpoint();

  /** Moves the checkpoint to {@code block}. */
  void saveCheckpoint(long block);
}
