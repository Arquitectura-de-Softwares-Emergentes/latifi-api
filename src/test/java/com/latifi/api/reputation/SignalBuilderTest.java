package com.latifi.api.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventType;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanStatus;
import com.latifi.api.reputation.domain.BorrowerLoanHistory;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.SignalBuilder;
import com.latifi.api.reputation.domain.SignalType;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The derivation of signals from loans, events and the profile, with no database. */
class SignalBuilderTest {

  private static final long FUNDED_AT = 1_800_000_000L;
  private static final long DAY = 86_400L;
  private static final long FIRST_DUE = FUNDED_AT + 30 * DAY;
  private static final BigInteger PRINCIPAL = BigInteger.valueOf(1_000_000_000L);

  /** Loan 7 of the write-up: 1000000000 units, 1000 bps, 90 days, 3 installments. */
  private static LoanState loan(BigInteger principal, int installments, Instant fundedAt) {
    return new LoanState(
        BigInteger.valueOf(7),
        "0x1111111111111111111111111111111111111111",
        fundedAt == null ? null : "0x2222222222222222222222222222222222222222",
        principal,
        1000,
        90,
        installments,
        fundedAt == null ? LoanStatus.OPEN : LoanStatus.ACTIVE,
        null,
        BigInteger.ZERO,
        0,
        Instant.ofEpochSecond(FUNDED_AT - 100),
        fundedAt,
        null,
        null);
  }

  private static ChainEvent repaid(long block, int logIndex, long amount, boolean onTime, long at) {
    return new ChainEvent(
        "0x" + String.format("%064x", block * 100 + logIndex),
        logIndex,
        block,
        "0x" + String.format("%064x", block),
        Instant.ofEpochSecond(at),
        ChainEventType.LOAN_REPAID,
        BigInteger.valueOf(7),
        Map.of("amountPaid", Long.toString(amount), "onTime", Boolean.toString(onTime)));
  }

  @Test
  void anOnTimeCompletedInstallmentGivesOneSignalWithItsSourceKeyAndPosition() {
    ChainEvent event = repaid(14, 2, 341_552_511L, true, FIRST_DUE - 1_000);
    BorrowerLoanHistory history =
        new BorrowerLoanHistory(
            loan(PRINCIPAL, 3, Instant.ofEpochSecond(FUNDED_AT)), List.of(event));

    List<ReputationSignal> signals = SignalBuilder.build(Optional.empty(), List.of(history));

    assertThat(signals).hasSize(1);
    ReputationSignal signal = signals.get(0);
    assertThat(signal.type()).isEqualTo(SignalType.REPAID_ON_TIME);
    assertThat(signal.sourceKey()).isEqualTo("tx:" + event.txHash() + ":2");
    assertThat(signal.principal()).isEqualTo(PRINCIPAL);
    assertThat(signal.blockNumber()).isEqualTo(14);
    assertThat(signal.logIndex()).isEqualTo(2);
    assertThat(signal.txHash()).isEqualTo(event.txHash());
    assertThat(signal.occurredAt()).isEqualTo(Instant.ofEpochSecond(FIRST_DUE - 1_000));
    assertThat(signal.loanId()).isEqualTo("7");
  }

  private static ChainEvent defaulted(long block, int logIndex, long at) {
    return new ChainEvent(
        "0x" + String.format("%064x", block * 100 + logIndex),
        logIndex,
        block,
        "0x" + String.format("%064x", block),
        Instant.ofEpochSecond(at),
        ChainEventType.LOAN_DEFAULTED,
        BigInteger.valueOf(7),
        Map.of("borrower", "0x1111111111111111111111111111111111111111", "outstandingAmount", "1"));
  }

  private static BorrowerLoanHistory threeInstallments(ChainEvent... events) {
    return new BorrowerLoanHistory(
        loan(PRINCIPAL, 3, Instant.ofEpochSecond(FUNDED_AT)), List.of(events));
  }

  private static List<ReputationSignal> build(BorrowerLoanHistory history) {
    return SignalBuilder.build(Optional.empty(), List.of(history));
  }

  @Test
  void aPartialPaymentGivesNoSignalAndStillAddsToThePaidTotal() {
    // 100 units first, then the rest of installment 1 (341552411) completes it.
    List<ReputationSignal> signals =
        build(
            threeInstallments(
                repaid(14, 0, 100, true, FIRST_DUE - 500),
                repaid(15, 0, 341_552_411L, true, FIRST_DUE - 400)));

    assertThat(signals).hasSize(1);
    assertThat(signals.get(0).type()).isEqualTo(SignalType.REPAID_ON_TIME);
    assertThat(signals.get(0).blockNumber()).isEqualTo(15);
  }

  @Test
  void aPaymentThatDoesNotMatchTheRemainingAmountNeverCounts() {
    List<ReputationSignal> signals =
        build(threeInstallments(repaid(14, 0, 341_552_512L, true, FIRST_DUE - 500)));

    assertThat(signals).isEmpty();
  }

  @Test
  void oneUnitOnTimeThenTheRemainderLateIsOneLateInstallment() {
    long late = FIRST_DUE + 5 * DAY;
    List<ReputationSignal> signals =
        build(
            threeInstallments(
                repaid(14, 0, 1, true, FIRST_DUE - 500), repaid(16, 0, 341_552_510L, false, late)));

    assertThat(signals).hasSize(1);
    assertThat(signals.get(0).type()).isEqualTo(SignalType.REPAID_LATE);
    assertThat(signals.get(0).daysLate()).isEqualTo(5);
    assertThat(signals.get(0).blockNumber()).isEqualTo(16);
  }

  @Test
  void aLatePaymentWhoseBlockTimeIsBeforeTheDueDateStillCountsOneDay() {
    List<ReputationSignal> signals =
        build(threeInstallments(repaid(14, 0, 341_552_511L, false, FIRST_DUE - 10)));

    assertThat(signals).hasSize(1);
    assertThat(signals.get(0).type()).isEqualTo(SignalType.REPAID_LATE);
    assertThat(signals.get(0).daysLate()).isEqualTo(1);
  }

  @Test
  void theEventFlagDecidesEvenWhenTheBlockTimeIsAfterTheDueDate() {
    List<ReputationSignal> signals =
        build(threeInstallments(repaid(14, 0, 341_552_511L, true, FIRST_DUE + 3 * DAY)));

    assertThat(signals).hasSize(1);
    assertThat(signals.get(0).type()).isEqualTo(SignalType.REPAID_ON_TIME);
  }

  @Test
  void theSecondInstallmentIsJudgedAgainstItsOwnDueDate() {
    long secondDue = FUNDED_AT + 60 * DAY;
    List<ReputationSignal> signals =
        build(
            threeInstallments(
                repaid(14, 0, 341_552_511L, true, FIRST_DUE - 500),
                repaid(15, 0, 341_552_511L, false, secondDue + 2 * DAY)));

    assertThat(signals)
        .extracting(ReputationSignal::type)
        .containsExactly(SignalType.REPAID_ON_TIME, SignalType.REPAID_LATE);
    assertThat(signals.get(1).daysLate()).isEqualTo(2);
  }

  @Test
  void aDefaultGivesADefaultedSignalWithDaysAgainstTheCurrentInstallment() {
    long secondDue = FUNDED_AT + 60 * DAY;
    List<ReputationSignal> signals =
        build(
            threeInstallments(
                repaid(14, 0, 341_552_511L, true, FIRST_DUE - 500),
                defaulted(20, 1, secondDue + 8 * DAY)));

    assertThat(signals)
        .extracting(ReputationSignal::type)
        .containsExactly(SignalType.REPAID_ON_TIME, SignalType.DEFAULTED);
    assertThat(signals.get(1).daysLate()).isEqualTo(8);
    assertThat(signals.get(1).sourceKey()).endsWith(":1");
  }

  @Test
  void eventsAfterADefaultAreIgnored() {
    List<ReputationSignal> signals =
        build(
            threeInstallments(
                defaulted(20, 0, FIRST_DUE + 8 * DAY),
                repaid(21, 0, 341_552_511L, true, FIRST_DUE + 9 * DAY)));

    assertThat(signals).extracting(ReputationSignal::type).containsExactly(SignalType.DEFAULTED);
  }

  @Test
  void aLoanThatWasNeverFundedGivesNoSignal() {
    BorrowerLoanHistory history =
        new BorrowerLoanHistory(
            loan(PRINCIPAL, 3, null), List.of(repaid(14, 0, 341_552_511L, true, FIRST_DUE)));

    assertThat(build(history)).isEmpty();
  }

  @Test
  void aDustLoanCompletesOnItsSinglePayment() {
    // Principal 1 gives a total due of 1, smaller than the 6 installments, so base is 0.
    BorrowerLoanHistory dust =
        new BorrowerLoanHistory(
            loan(BigInteger.ONE, 6, Instant.ofEpochSecond(FUNDED_AT)),
            List.of(repaid(14, 0, 1, true, FIRST_DUE)));

    List<ReputationSignal> signals = build(dust);

    assertThat(signals).hasSize(1);
    assertThat(signals.get(0).principal()).isEqualTo(BigInteger.ONE);
  }

  @Test
  void everySignalCarriesThePrincipalOfItsLoan() {
    List<ReputationSignal> signals =
        build(
            threeInstallments(
                repaid(14, 0, 341_552_511L, true, FIRST_DUE - 500),
                repaid(15, 0, 341_552_511L, true, FIRST_DUE + 10),
                defaulted(30, 0, FUNDED_AT + 100 * DAY)));

    assertThat(signals).hasSize(3).allSatisfy(s -> assertThat(s.principal()).isEqualTo(PRINCIPAL));
  }

  @Test
  void theProfileSignalHasTheProfileSourceKeyAndNoTransaction() {
    Instant created = Instant.parse("2026-01-01T12:00:00Z");

    List<ReputationSignal> signals = SignalBuilder.build(Optional.of(created), List.of());

    assertThat(signals).hasSize(1);
    ReputationSignal profile = signals.get(0);
    assertThat(profile.type()).isEqualTo(SignalType.PROFILE_COMPLETED);
    assertThat(profile.sourceKey()).isEqualTo("profile");
    assertThat(profile.occurredAt()).isEqualTo(created);
    assertThat(profile.blockNumber()).isZero();
    assertThat(profile.txHash()).isNull();
    assertThat(profile.loanId()).isNull();
  }

  @Test
  void eventsGivenOutOfOrderAreWalkedInChainOrder() {
    List<ReputationSignal> signals =
        build(
            threeInstallments(
                repaid(15, 0, 341_552_411L, true, FIRST_DUE - 400),
                repaid(14, 0, 100, true, FIRST_DUE - 500)));

    assertThat(signals).hasSize(1);
    assertThat(signals.get(0).blockNumber()).isEqualTo(15);
  }
}
