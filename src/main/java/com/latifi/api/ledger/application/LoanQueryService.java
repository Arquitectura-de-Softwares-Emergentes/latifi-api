package com.latifi.api.ledger.application;

import com.latifi.api.ledger.domain.LoanQueryRepository;
import com.latifi.api.ledger.domain.LoanSchedule;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanView;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import com.latifi.api.shared.Uint256;
import java.math.BigInteger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves the loan mirror to the API. */
@Service
public class LoanQueryService {

  private final LoanQueryRepository loans;

  public LoanQueryService(LoanQueryRepository loans) {
    this.loans = loans;
  }

  /** One loan by id. A malformed id answers 400 and an unknown id answers 404 LOAN_NOT_FOUND. */
  @Transactional(readOnly = true)
  public LoanView get(String loanIdText) {
    BigInteger loanId = Uint256.parse(loanIdText);
    return loans
        .find(loanId)
        .map(LoanQueryService::withTotalDue)
        .orElseThrow(() -> new ApiException(ApiErrorCode.LOAN_NOT_FOUND));
  }

  /**
   * An open request has no stored total, because the contract fixes it at the funding. The response
   * shows the value the contract will store, computed from the terms of the request. Every other
   * loan keeps its stored total.
   */
  static LoanView withTotalDue(LoanView view) {
    LoanState loan = view.loan();
    if (loan.totalDue() != null) {
      return view;
    }
    BigInteger projected =
        new LoanSchedule(
                loan.principal(), loan.interestRateBps(), loan.termDays(), loan.installments(), 0L)
            .totalDue();
    return view.withLoan(
        new LoanState(
            loan.loanId(),
            loan.borrower(),
            loan.lender(),
            loan.principal(),
            loan.interestRateBps(),
            loan.termDays(),
            loan.installments(),
            loan.status(),
            projected,
            loan.totalPaid(),
            loan.installmentsPaid(),
            loan.requestedAt(),
            loan.fundedAt(),
            loan.finalDueDate(),
            loan.nextDueDate()));
  }
}
