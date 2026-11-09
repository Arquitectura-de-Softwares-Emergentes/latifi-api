package com.latifi.api.reputation.domain;

import java.time.Instant;
import java.util.Optional;

/** Port to the ledger and the state of each address. */
public interface ReputationRepository {

  /**
   * Waits for the lock of one address until the end of the running transaction. Every recompute
   * takes it first, because the indexer batch and a profile save can touch the same address from
   * different transactions.
   */
  void lock(String address);

  /**
   * Makes the stored ledger and state of the address equal to {@code result}. Entries are matched
   * by source key and updated in place, so the id of an entry never changes. Stored entries that
   * the result no longer holds are deleted.
   */
  void replace(String address, ReputationResult result, Instant updatedAt);

  /** Removes the ledger and the state of the address. */
  void delete(String address);

  Optional<ReputationState> findState(String address);

  /**
   * The entries of the address, newest first.
   *
   * @param typeOrNull keeps only this type when set
   * @param page from 0
   * @param size from 1 to 100, validated by the caller
   */
  HistoryPage history(String address, SignalType typeOrNull, int page, int size);
}
