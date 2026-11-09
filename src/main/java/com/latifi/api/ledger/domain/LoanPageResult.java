package com.latifi.api.ledger.domain;

import java.util.List;

/**
 * One page of the feed.
 *
 * @param totalItems every loan that matches the filters, not only the ones on this page
 */
public record LoanPageResult(List<LoanView> items, int page, int size, long totalItems) {

  public LoanPageResult {
    items = List.copyOf(items);
  }
}
