package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.util.Optional;

/** Read side of the loan mirror: one loan, or a filtered and sorted page, with the reputation. */
public interface LoanQueryRepository {

  /** The loan with the reputation of its borrower, or empty when no such loan is mirrored. */
  Optional<LoanView> find(BigInteger loanId);

  /** One page of the feed. A page past the end has no items and still reports the total. */
  LoanPageResult search(LoanQuery query);
}
