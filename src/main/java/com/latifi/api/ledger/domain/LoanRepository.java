package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/** Storage port of the loan read model. */
public interface LoanRepository {

  /** Inserts the loan or replaces every column of the stored one. */
  void upsert(LoanState state);

  /** Removes the loan. It does nothing when the loan is not stored. */
  void delete(BigInteger loanId);

  Optional<LoanState> find(BigInteger loanId);

  /** The loans of a borrower (lowercase address), ordered by loan id. */
  List<LoanState> findByBorrower(String borrower);
}
