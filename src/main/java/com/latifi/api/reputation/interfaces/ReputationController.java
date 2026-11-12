package com.latifi.api.reputation.interfaces;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.latifi.api.reputation.application.ReputationService;
import com.latifi.api.reputation.domain.HistoryPage;
import com.latifi.api.reputation.domain.ReputationState;
import com.latifi.api.reputation.domain.SignalType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reputation endpoints. The {@code /api/v1} prefix comes from WebConfig and every route needs a
 * valid token. An unknown {@code type} value fails the enum conversion and answers 400.
 */
@RestController
@Tag(name = "Reputation")
@RequestMapping("/reputation")
public class ReputationController {

  private final ReputationService reputation;

  public ReputationController(ReputationService reputation) {
    this.reputation = reputation;
  }

  @GetMapping("/{address}")
  @Operation(summary = "Read the reputation of an address")
  public ReputationResponse getReputation(@PathVariable String address) {
    return ReputationResponse.from(reputation.get(address));
  }

  @GetMapping("/{address}/history")
  @Operation(summary = "List the reputation events behind a score")
  public ReputationHistoryPage getReputationHistory(
      @PathVariable String address,
      @RequestParam(required = false) SignalType type,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ReputationHistoryPage.from(reputation.history(address, type, page, size));
  }

  public record ReputationResponse(
      String address,
      int score,
      String level,
      int onTimeCount,
      int lateCount,
      int defaultCount,
      boolean profileComplete,
      Instant updatedAt) {

    static ReputationResponse from(ReputationState state) {
      return new ReputationResponse(
          state.address(),
          state.score(),
          state.level().name(),
          state.onTimeCount(),
          state.lateCount(),
          state.defaultCount(),
          state.profileComplete(),
          state.updatedAt());
    }
  }

  /** Null fields are omitted: the profile entry has no loan, days late or transaction. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReputationEventDto(
      long id,
      String loanId,
      SignalType type,
      int delta,
      int scoreAfter,
      Integer daysLate,
      String txHash,
      Integer logIndex,
      Instant occurredAt) {

    static ReputationEventDto from(HistoryPage.Entry entry) {
      return new ReputationEventDto(
          entry.id(),
          entry.loanId(),
          entry.type(),
          entry.delta(),
          entry.scoreAfter(),
          entry.daysLate(),
          entry.txHash(),
          entry.logIndex(),
          entry.occurredAt());
    }
  }

  public record ReputationHistoryPage(
      List<ReputationEventDto> items, int page, int size, long totalItems) {

    static ReputationHistoryPage from(HistoryPage page) {
      return new ReputationHistoryPage(
          page.items().stream().map(ReputationEventDto::from).toList(),
          page.page(),
          page.size(),
          page.totalItems());
    }
  }
}
