package com.latifi.api.ledger.domain;

/** Where a loan stands. OPEN waits for a lender, REPAID and DEFAULTED are final. */
public enum LoanStatus {
  OPEN,
  ACTIVE,
  REPAID,
  DEFAULTED
}
