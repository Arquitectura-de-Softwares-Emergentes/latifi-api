package com.latifi.api.ledger.domain;

import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;

/**
 * The sort keys of the loan feed. Each key carries its own fixed ORDER BY fragment, and that
 * fragment is the only way a sort reaches the SQL text. Every fragment ends with the loan id
 * descending, so two loans never tie and a page boundary never repeats or skips a loan.
 */
public enum LoanSort {
  /** Borrower score, highest first. A borrower without a row counts as 500. */
  REPUTATION("reputation", "coalesce(rs.score, 500) desc, l.loan_id desc"),
  /** Principal, largest first. */
  AMOUNT("amount", "l.principal desc, l.loan_id desc"),
  /** Term, shortest first. */
  TERM("term", "l.term_days asc, l.loan_id desc"),
  /** Interest rate, highest first. */
  RATE("rate", "l.interest_rate_bps desc, l.loan_id desc"),
  /** Request time, newest first. */
  RECENT("recent", "l.requested_at desc, l.loan_id desc"),
  /** Next installment due date, soonest first. Loans without a pending installment come last. */
  NEXT_DUE("nextDue", "l.next_due_date asc nulls last, l.loan_id desc");

  private final String apiName;
  private final String orderBy;

  LoanSort(String apiName, String orderBy) {
    this.apiName = apiName;
    this.orderBy = orderBy;
  }

  /** The name that the contract uses in the {@code sort} query parameter. */
  public String apiName() {
    return apiName;
  }

  /** The fixed ORDER BY fragment, without the keywords {@code order by}. */
  public String orderBy() {
    return orderBy;
  }

  /** Maps the text of the query parameter. Null means the default, an unknown name answers 400. */
  public static LoanSort fromApi(String name) {
    if (name == null) {
      return REPUTATION;
    }
    for (LoanSort sort : values()) {
      if (sort.apiName.equals(name)) {
        return sort;
      }
    }
    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
  }
}
