package com.latifi.api.ledger.application;

import com.latifi.api.ledger.domain.BorrowerLevel;
import com.latifi.api.ledger.domain.LoanPageResult;
import com.latifi.api.ledger.domain.LoanQuery;
import com.latifi.api.ledger.domain.LoanQueryRepository;
import com.latifi.api.ledger.domain.LoanSchedule;
import com.latifi.api.ledger.domain.LoanSort;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanStatus;
import com.latifi.api.ledger.domain.LoanView;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import com.latifi.api.shared.EthAddress;
import com.latifi.api.shared.Uint256;
import java.math.BigInteger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves the loan mirror to the API. */
@Service
public class LoanQueryService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

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
   * One page of the feed. Every parameter arrives as raw request text or a number, and this method
   * is the single place that validates it. An unknown status, level or sort, a malformed number, a
   * term below 1, a page below 0 and a size outside 1 to 100 answer 400 VALIDATION_FAILED, and a
   * malformed address answers 400 INVALID_ADDRESS. A minimum amount above the maximum is not an
   * error: no loan matches, so the page is empty.
   */
  @Transactional(readOnly = true)
  public LoanPageResult search(
      String status,
      String minAmount,
      String maxAmount,
      Integer maxTermDays,
      String minLevel,
      String borrower,
      String lender,
      String sort,
      Integer page,
      Integer size) {
    int pageIndex = page == null ? 0 : page;
    int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
    if (pageIndex < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
    if (maxTermDays != null && maxTermDays < 1) {
      throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
    LoanQuery query =
        new LoanQuery(
            status == null ? null : parseStatus(status),
            minAmount == null ? null : Uint256.parse(minAmount),
            maxAmount == null ? null : Uint256.parse(maxAmount),
            maxTermDays,
            minLevel == null ? null : BorrowerLevel.parse(minLevel).rank(),
            borrower == null ? null : EthAddress.parse(borrower).lowercase(),
            lender == null ? null : EthAddress.parse(lender).lowercase(),
            LoanSort.fromApi(sort),
            pageIndex,
            pageSize);
    LoanPageResult result = loans.search(query);
    return new LoanPageResult(
        result.items().stream().map(LoanQueryService::withTotalDue).toList(),
        result.page(),
        result.size(),
        result.totalItems());
  }

  private static LoanStatus parseStatus(String status) {
    for (LoanStatus candidate : LoanStatus.values()) {
      if (candidate.name().equals(status)) {
        return candidate;
      }
    }
    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
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
