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

  /** The last block that a finished batch covered, or empty before the first batch. */
  OptionalLong checkpoint();

  /** Moves the checkpoint to {@code block}. */
  void saveCheckpoint(long block);
}
