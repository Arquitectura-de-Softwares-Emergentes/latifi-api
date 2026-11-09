package com.latifi.api.ledger.interfaces;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.latifi.api.ledger.application.LoanQueryService;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanView;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan endpoints. The {@code /api/v1} prefix comes from WebConfig and every route needs a valid
 * token. The loan id and every other parameter reach the service as text, so a malformed value
 * answers 400 VALIDATION_FAILED from one place.
 */
@RestController
@RequestMapping("/loans")
public class LoanController {

  private final LoanQueryService loans;

  public LoanController(LoanQueryService loans) {
    this.loans = loans;
  }

  @GetMapping("/{loanId}")
  public LoanSummaryDto get(@PathVariable String loanId) {
    return LoanSummaryDto.from(loans.get(loanId));
  }

  /** Reputation of the borrower of a loan. */
  public record BorrowerReputationDto(int score, String level) {}

  /**
   * The LoanSummary of the contract. Amounts and the loan id are digit strings, times are ISO
   * instants, and null fields are left out.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record LoanSummaryDto(
      String loanId,
      String borrower,
      String lender,
      String principal,
      int interestRateBps,
      int termDays,
      int installments,
      String status,
      String amountRepaid,
      String totalDue,
      int installmentsPaid,
      Instant requestedAt,
      Instant fundedAt,
      Instant finalDueDate,
      Instant nextDueDate,
      BorrowerReputationDto borrowerReputation) {

    static LoanSummaryDto from(LoanView view) {
      LoanState loan = view.loan();
      return new LoanSummaryDto(
          loan.loanId().toString(),
          loan.borrower(),
          loan.lender(),
          loan.principal().toString(),
          loan.interestRateBps(),
          loan.termDays(),
          loan.installments(),
          loan.status().name(),
          loan.totalPaid().toString(),
          loan.totalDue().toString(),
          loan.installmentsPaid(),
          loan.requestedAt(),
          loan.fundedAt(),
          loan.finalDueDate(),
          loan.nextDueDate(),
          new BorrowerReputationDto(view.borrowerScore(), view.borrowerLevel()));
    }
  }
}
