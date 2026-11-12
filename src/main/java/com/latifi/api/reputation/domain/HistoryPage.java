package com.latifi.api.reputation.domain;

import java.time.Instant;
import java.util.List;

/**
 * One page of the history of an address, newest entry first.
 *
 * @param totalItems entries that match the filter, across all pages
 */
public record HistoryPage(List<Entry> items, int page, int size, long totalItems) {

  public HistoryPage {
    items = List.copyOf(items);
  }

  /**
   * One stored ledger entry. {@code loanId}, {@code daysLate}, {@code txHash} and {@code logIndex}
   * are null when the entry has no such value.
   */
  public record Entry(
      long id,
      SignalType type,
      String loanId,
      int delta,
      int scoreAfter,
      Integer daysLate,
      String txHash,
      Integer logIndex,
      Instant occurredAt) {}
}
