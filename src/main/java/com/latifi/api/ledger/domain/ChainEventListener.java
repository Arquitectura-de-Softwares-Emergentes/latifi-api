package com.latifi.api.ledger.domain;

/**
 * Hears about every batch that inserted or removed rows. The indexer calls it inside the
 * transaction of that batch, so whatever the listener writes commits or rolls back together with
 * the events.
 */
public interface ChainEventListener {

  void onBatchApplied(BatchDelta delta);
}
