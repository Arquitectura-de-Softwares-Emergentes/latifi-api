package com.latifi.api.ledger.interfaces;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.latifi.api.ledger.application.LoanQueryService;
import com.latifi.api.ledger.domain.LoanPageResult;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanView;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  /**
   * The feed. Every parameter is plain text or a nullable number, so a malformed value reaches the
   * validation of the service, or the type mismatch handler, and never a framework default.
   */
  @GetMapping
  public LoanPageDto list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String minAmount,
      @RequestParam(required = false) String maxAmount,
      @RequestParam(required = false) Integer maxTermDays,
      @RequestParam(required = false) String minLevel,
      @RequestParam(required = false) String borrower,
      @RequestParam(required = false) String lender,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return LoanPageDto.from(
        loans.search(
            status,
            minAmount,
            maxAmount,
            maxTermDays,
            minLevel,
            borrower,
            lender,
            sort,
            page,
            size));
  }

  @GetMapping("/{loanId}")
  public LoanSummaryDto get(@PathVariable String loanId) {
    return LoanSummaryDto.from(loans.get(loanId));
  }

  /** The LoanPage of the contract. */
  public record LoanPageDto(List<LoanSummaryDto> items, int page, int size, long totalItems) {

    static LoanPageDto from(LoanPageResult result) {
      return new LoanPageDto(
          result.items().stream().map(LoanSummaryDto::from).toList(),
          result.page(),
          result.size(),
          result.totalItems());
    }
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
