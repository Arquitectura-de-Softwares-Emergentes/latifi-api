package com.latifi.api.ledger.domain;

/** The four LoanBook events of the frozen v0 ABI that the indexer mirrors. */
public enum ChainEventType {
  LOAN_REQUESTED,
  LOAN_FUNDED,
  LOAN_REPAID,
  LOAN_DEFAULTED
}
