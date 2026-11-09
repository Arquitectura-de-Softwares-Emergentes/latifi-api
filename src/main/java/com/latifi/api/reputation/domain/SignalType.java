package com.latifi.api.reputation.domain;

/** The facts that can move a score. Three come from the chain and one from the profile. */
public enum SignalType {
  /** The profile has a name and a contact. It has no transaction behind it. */
  PROFILE_COMPLETED,
  /** A payment completed an installment on or before its due time. */
  REPAID_ON_TIME,
  /** A payment completed an installment after its due time. */
  REPAID_LATE,
  /** A {@code LoanDefaulted} event. */
  DEFAULTED
}
