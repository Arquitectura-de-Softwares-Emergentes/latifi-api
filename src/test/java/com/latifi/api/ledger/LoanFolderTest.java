package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventType;
import com.latifi.api.ledger.domain.LoanFolder;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanStatus;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoanFolderTest {

  private static final BigInteger LOAN = BigInteger.valueOf(7);
  private static final String BORROWER = "0x1111111111111111111111111111111111111111";
  private static final String LENDER = "0x2222222222222222222222222222222222222222";
  private static final long FUNDED_AT = 1_800_000_000L;
  private static final long DAY = 86_400L;

  private static BigInteger big(long value) {
    return BigInteger.valueOf(value);
  }

  private static ChainEvent event(
      ChainEventType type, long block, int logIndex, Map<String, String> payload) {
    return new ChainEvent(
        "0x" + String.format("%064x", block * 100 + logIndex),
        logIndex,
        block,
        "0x" + String.format("%064x", block),
        Instant.ofEpochSecond(1_700_000_000L + block),
        type,
        LOAN,
        payload);
  }

  private static ChainEvent requested(
      long block, long principal, int rateBps, int termDays, int installments) {
    return event(
        ChainEventType.LOAN_REQUESTED,
        block,
        0,
        Map.of(
            "borrower", BORROWER,
            "amount", String.valueOf(principal),
            "interestRateBps", String.valueOf(rateBps),
            "termDays", String.valueOf(termDays),
            "installments", String.valueOf(installments)));
  }

  private static ChainEvent funded(long block, long fundedAt, int termDays) {
    return event(
        ChainEventType.LOAN_FUNDED,
        block,
        0,
        Map.of(
            "lender",
            LENDER,
            "borrower",
            BORROWER,
            "amount",
            "1",
            "fundedAt",
            String.valueOf(fundedAt),
            "dueDate",
            String.valueOf(fundedAt + termDays * DAY)));
  }

  private static ChainEvent repaid(long block, long amount) {
    return event(
        ChainEventType.LOAN_REPAID,
        block,
        0,
        Map.of("amountPaid", String.valueOf(amount), "onTime", "true"));
  }

  private static ChainEvent defaulted(long block) {
    return event(
        ChainEventType.LOAN_DEFAULTED,
        block,
        0,
        Map.of("borrower", BORROWER, "outstandingAmount", "1"));
  }

  /** The LOANBOOK.md example, funded at block 20. */
  private static List<ChainEvent> standardFunded() {
    return new ArrayList<>(
        List.of(requested(10, 1_000_000_000L, 1000, 90, 3), funded(20, FUNDED_AT, 90)));
  }

  private static LoanState fold(List<ChainEvent> events) {
    Optional<LoanState> state = LoanFolder.fold(events);
    assertThat(state).isPresent();
    return state.get();
  }

  private static Instant at(long epochSeconds) {
    return Instant.ofEpochSecond(epochSeconds);
  }

  @Test
  void noEventsGiveNoLoan() {
    assertThat(LoanFolder.fold(List.of())).isEmpty();
  }

  @Test
  void paymentsAndDefaultsWithoutARequestGiveNoLoan() {
    assertThat(LoanFolder.fold(List.of(repaid(12, 100), defaulted(14), funded(11, FUNDED_AT, 90))))
        .isEmpty();
  }

  @Test
  void aRequestGivesAnOpenLoanWithItsTerms() {
    LoanState loan = fold(List.of(requested(10, 1_000_000_000L, 1000, 90, 3)));

    assertThat(loan.status()).isEqualTo(LoanStatus.OPEN);
    assertThat(loan.borrower()).isEqualTo(BORROWER);
    assertThat(loan.principal()).isEqualTo(big(1_000_000_000L));
    assertThat(loan.interestRateBps()).isEqualTo(1000);
    assertThat(loan.termDays()).isEqualTo(90);
    assertThat(loan.installments()).isEqualTo(3);
    assertThat(loan.requestedAt()).isEqualTo(at(1_700_000_010L));
    assertThat(loan.lender()).isNull();
    assertThat(loan.totalDue()).isNull();
    assertThat(loan.totalPaid()).isEqualTo(BigInteger.ZERO);
    assertThat(loan.installmentsPaid()).isZero();
    assertThat(loan.fundedAt()).isNull();
    assertThat(loan.finalDueDate()).isNull();
    assertThat(loan.nextDueDate()).isNull();
  }

  @Test
  void fundingMakesTheLoanActiveWithTheLoanBookSchedule() {
    LoanState loan = fold(standardFunded());

    assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
    assertThat(loan.lender()).isEqualTo(LENDER);
    assertThat(loan.totalDue()).isEqualTo(big(1_024_657_534L));
    assertThat(loan.fundedAt()).isEqualTo(at(FUNDED_AT));
    assertThat(loan.finalDueDate()).isEqualTo(at(FUNDED_AT + 90 * DAY));
    assertThat(loan.nextDueDate()).isEqualTo(at(FUNDED_AT + 30 * DAY));
    assertThat(loan.installmentsPaid()).isZero();
    assertThat(loan.totalPaid()).isEqualTo(BigInteger.ZERO);
  }

  @Test
  void aFullFirstInstallmentMovesTheNextDueDateToTheSecond() {
    List<ChainEvent> events = standardFunded();
    events.add(repaid(30, 341_552_511L));

    LoanState loan = fold(events);

    assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
    assertThat(loan.installmentsPaid()).isEqualTo(1);
    assertThat(loan.nextDueDate()).isEqualTo(at(FUNDED_AT + 60 * DAY));
    assertThat(loan.totalPaid()).isEqualTo(big(341_552_511L));
  }

  @Test
  void aPartialPaymentDoesNotCompleteAnInstallment() {
    List<ChainEvent> events = standardFunded();
    events.add(repaid(30, 100));

    LoanState loan = fold(events);

    assertThat(loan.installmentsPaid()).isZero();
    assertThat(loan.nextDueDate()).isEqualTo(at(FUNDED_AT + 30 * DAY));
    assertThat(loan.totalPaid()).isEqualTo(big(100));
  }

  @Test
  void theThirdPaymentRepaysTheLoan() {
    List<ChainEvent> events = standardFunded();
    events.add(repaid(30, 341_552_511L));
    events.add(repaid(40, 341_552_511L));
    events.add(repaid(50, 341_552_512L));

    LoanState loan = fold(events);

    assertThat(loan.status()).isEqualTo(LoanStatus.REPAID);
    assertThat(loan.installmentsPaid()).isEqualTo(3);
    assertThat(loan.totalPaid()).isEqualTo(loan.totalDue());
    assertThat(loan.nextDueDate()).isNull();
  }

  @Test
  void aSinglePaymentLoanIsRepaidByOnePayment() {
    // total due: 1000000000 + floor(1000000000 * 1000 * 30 / 3650000) = 1008219178
    LoanState loan =
        fold(
            List.of(
                requested(10, 1_000_000_000L, 1000, 30, 1),
                funded(20, FUNDED_AT, 30),
                repaid(30, 1_008_219_178L)));

    assertThat(loan.totalDue()).isEqualTo(big(1_008_219_178L));
    assertThat(loan.status()).isEqualTo(LoanStatus.REPAID);
    assertThat(loan.installmentsPaid()).isEqualTo(1);
    assertThat(loan.nextDueDate()).isNull();
  }

  @Test
  void aDustLoanCompletesOnItsOnlyPayment() {
    List<ChainEvent> events = new ArrayList<>(List.of(requested(10, 1, 1000, 90, 6)));
    events.add(funded(20, FUNDED_AT, 90));

    LoanState active = fold(events);

    assertThat(active.totalDue()).isEqualTo(BigInteger.ONE);
    assertThat(active.status()).isEqualTo(LoanStatus.ACTIVE);
    assertThat(active.installmentsPaid()).isZero();
    assertThat(active.nextDueDate()).isEqualTo(at(FUNDED_AT + 90 * DAY));

    events.add(repaid(30, 1));
    LoanState repaid = fold(events);

    assertThat(repaid.status()).isEqualTo(LoanStatus.REPAID);
    assertThat(repaid.installmentsPaid()).isEqualTo(6);
  }

  @Test
  void aDefaultOnAnActiveLoanIsFinal() {
    List<ChainEvent> events = standardFunded();
    events.add(defaulted(40));

    LoanState defaulted = fold(events);

    assertThat(defaulted.status()).isEqualTo(LoanStatus.DEFAULTED);
    assertThat(defaulted.nextDueDate()).isNull();

    events.add(repaid(50, 341_552_511L));
    LoanState afterPayment = fold(events);

    assertThat(afterPayment.status()).isEqualTo(LoanStatus.DEFAULTED);
    assertThat(afterPayment.totalPaid()).isEqualTo(BigInteger.ZERO);
    assertThat(afterPayment.installmentsPaid()).isZero();
  }

  @Test
  void aDefaultOnAnOpenLoanIsIgnored() {
    LoanState loan = fold(List.of(requested(10, 1_000_000_000L, 1000, 90, 3), defaulted(15)));

    assertThat(loan.status()).isEqualTo(LoanStatus.OPEN);
  }

  @Test
  void aFundingThatArrivesWhileTheLoanIsNotOpenIsIgnored() {
    List<ChainEvent> events = standardFunded();
    events.add(funded(25, FUNDED_AT + 999, 90));

    LoanState loan = fold(events);

    assertThat(loan.fundedAt()).isEqualTo(at(FUNDED_AT));
    assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
  }

  @Test
  void aSecondRequestForTheSameLoanIsIgnored() {
    List<ChainEvent> events = new ArrayList<>();
    events.add(requested(10, 1_000_000_000L, 1000, 90, 3));
    events.add(requested(11, 5, 2000, 30, 1));

    LoanState loan = fold(events);

    assertThat(loan.principal()).isEqualTo(big(1_000_000_000L));
    assertThat(loan.installments()).isEqualTo(3);
    assertThat(loan.requestedAt()).isEqualTo(at(1_700_000_010L));
  }

  @Test
  void eventsInAnyStorageOrderFoldToTheSameState() {
    List<ChainEvent> ordered = standardFunded();
    ordered.add(repaid(30, 341_552_511L));
    ordered.add(repaid(40, 100));
    List<ChainEvent> shuffled =
        List.of(ordered.get(3), ordered.get(1), ordered.get(0), ordered.get(2));

    assertThat(fold(shuffled)).isEqualTo(fold(ordered));
  }

  @Test
  void eventsInTheSameBlockFollowTheLogIndex() {
    ChainEvent request = requested(10, 1_000_000_000L, 1000, 90, 3);
    ChainEvent funding =
        new ChainEvent(
            "0x" + String.format("%064x", 99),
            5,
            10,
            request.blockHash(),
            request.blockTimestamp(),
            ChainEventType.LOAN_FUNDED,
            LOAN,
            funded(10, FUNDED_AT, 90).payload());

    assertThat(fold(List.of(funding, request)).status()).isEqualTo(LoanStatus.ACTIVE);
  }

  @Test
  void aHugePrincipalKeepsEveryDigit() {
    BigInteger principal = BigInteger.TWO.pow(200).subtract(BigInteger.ONE);
    ChainEvent request =
        event(
            ChainEventType.LOAN_REQUESTED,
            10,
            0,
            Map.of(
                "borrower", BORROWER,
                "amount", principal.toString(),
                "interestRateBps", "5000",
                "termDays", "365",
                "installments", "6"));

    LoanState loan = fold(List.of(request, funded(20, FUNDED_AT, 365)));

    BigInteger expected =
        principal.add(principal.multiply(big(5000)).multiply(big(365)).divide(big(3_650_000L)));
    assertThat(loan.totalDue()).isEqualTo(expected);
    assertThat(loan.principal()).isEqualTo(principal);
  }
}
