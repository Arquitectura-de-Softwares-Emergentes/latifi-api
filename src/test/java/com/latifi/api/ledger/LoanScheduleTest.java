package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latifi.api.ledger.domain.LoanSchedule;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class LoanScheduleTest {

  private static final BigInteger ONE_THOUSAND_TOKENS = BigInteger.valueOf(1_000_000_000L);

  private static BigInteger big(long value) {
    return BigInteger.valueOf(value);
  }

  /** The worked example of LOANBOOK.md: 1000 tokens, 1000 bps, 90 days, three installments. */
  private static LoanSchedule standard() {
    return new LoanSchedule(ONE_THOUSAND_TOKENS, 1000, 90, 3, 1_000_000L);
  }

  @Test
  void theTotalDueMatchesTheLoanBookExample() {
    assertThat(standard().totalDue()).isEqualTo(big(1_024_657_534L));
  }

  @Test
  void theInterestFloorsToZeroForAOneUnitPrincipal() {
    LoanSchedule schedule = new LoanSchedule(BigInteger.ONE, 1000, 90, 1, 0);

    assertThat(schedule.totalDue()).isEqualTo(BigInteger.ONE);
  }

  @Test
  void theTotalDueDoesNotOverflowAtAHugePrincipal() {
    BigInteger principal = BigInteger.TWO.pow(128).subtract(BigInteger.ONE).divide(BigInteger.TWO);
    LoanSchedule schedule = new LoanSchedule(principal, 5000, 365, 6, 0);

    BigInteger expected =
        principal.add(principal.multiply(big(5000)).multiply(big(365)).divide(big(3_650_000L)));
    assertThat(schedule.totalDue()).isEqualTo(expected);
  }

  @Test
  void theInstallmentsMatchTheLoanBookExample() {
    LoanSchedule schedule = standard();

    assertThat(schedule.baseInstallment()).isEqualTo(big(341_552_511L));
    assertThat(schedule.target(1)).isEqualTo(big(341_552_511L));
    assertThat(schedule.target(2)).isEqualTo(big(683_105_022L));
    assertThat(schedule.target(3)).isEqualTo(big(1_024_657_534L));
    assertThat(schedule.target(3).subtract(schedule.target(2))).isEqualTo(big(341_552_512L));
  }

  @Test
  void theInstallmentsMatchTheRepaymentAdrExample() {
    LoanSchedule schedule = new LoanSchedule(big(100_000_000L), 1000, 30, 2, 0);

    assertThat(schedule.totalDue()).isEqualTo(big(100_821_917L));
    assertThat(schedule.target(1)).isEqualTo(big(50_410_958L));
    assertThat(schedule.target(2).subtract(schedule.target(1))).isEqualTo(big(50_410_959L));
  }

  @Test
  void aFreshLoanOwesTheFirstInstallment() {
    LoanSchedule schedule = standard();

    assertThat(schedule.currentIndex(BigInteger.ZERO)).isEqualTo(1);
    assertThat(schedule.remaining(BigInteger.ZERO)).isEqualTo(big(341_552_511L));
  }

  @Test
  void payingTheFirstInstallmentMovesTheIndexToTheSecond() {
    LoanSchedule schedule = standard();

    assertThat(schedule.currentIndex(big(341_552_511L))).isEqualTo(2);
    assertThat(schedule.remaining(big(341_552_511L))).isEqualTo(big(341_552_511L));
  }

  @Test
  void aPartialPaymentKeepsTheIndexAndLowersTheRemainingAmount() {
    LoanSchedule schedule = standard();

    assertThat(schedule.currentIndex(big(100))).isEqualTo(1);
    assertThat(schedule.remaining(big(100))).isEqualTo(big(341_552_411L));
  }

  @Test
  void theLastInstallmentAbsorbsTheRemainder() {
    LoanSchedule schedule = standard();

    assertThat(schedule.currentIndex(big(683_105_022L))).isEqualTo(3);
    assertThat(schedule.remaining(big(683_105_022L))).isEqualTo(big(341_552_512L));
  }

  @Test
  void aSingleInstallmentLoanOwesTheWholeTotal() {
    LoanSchedule schedule = new LoanSchedule(ONE_THOUSAND_TOKENS, 1000, 90, 1, 0);

    assertThat(schedule.target(1)).isEqualTo(schedule.totalDue());
    assertThat(schedule.installmentsPaid(BigInteger.ZERO)).isZero();
    assertThat(schedule.installmentsPaid(schedule.totalDue().subtract(BigInteger.ONE))).isZero();
    assertThat(schedule.installmentsPaid(schedule.totalDue())).isEqualTo(1);
  }

  @Test
  void theDustCaseSitsEntirelyOnTheLastInstallment() {
    LoanSchedule schedule = new LoanSchedule(BigInteger.ONE, 1, 1, 6, 0);

    assertThat(schedule.totalDue()).isEqualTo(BigInteger.ONE);
    assertThat(schedule.baseInstallment()).isZero();
    assertThat(schedule.currentIndex(BigInteger.ZERO)).isEqualTo(6);
    assertThat(schedule.remaining(BigInteger.ZERO)).isEqualTo(BigInteger.ONE);
    assertThat(schedule.installmentsPaid(BigInteger.ZERO)).isZero();
    assertThat(schedule.installmentsPaid(BigInteger.ONE)).isEqualTo(6);
    assertThat(schedule.completesInstallment(BigInteger.ZERO, BigInteger.ONE)).isTrue();
  }

  @Test
  void aPaymentCompletesTheInstallmentOnlyWhenItEqualsTheRemainingAmount() {
    LoanSchedule schedule = standard();

    assertThat(schedule.completesInstallment(BigInteger.ZERO, big(341_552_511L))).isTrue();
    assertThat(schedule.completesInstallment(big(100), big(341_552_411L))).isTrue();
    assertThat(schedule.completesInstallment(big(341_552_511L), big(341_552_511L))).isTrue();
  }

  @Test
  void aOneUnitPaymentDoesNotCompleteALargeInstallment() {
    LoanSchedule schedule = standard();

    assertThat(schedule.completesInstallment(BigInteger.ZERO, BigInteger.ONE)).isFalse();
  }

  @Test
  void anOverpaymentDoesNotCompleteTheInstallment() {
    LoanSchedule schedule = standard();

    assertThat(schedule.completesInstallment(BigInteger.ZERO, big(341_552_512L))).isFalse();
  }

  @Test
  void aZeroPaymentCompletesNothing() {
    LoanSchedule schedule = standard();

    assertThat(schedule.completesInstallment(BigInteger.ZERO, BigInteger.ZERO)).isFalse();
  }

  @Test
  void theDueTimesFollowTheContractFormula() {
    LoanSchedule schedule = standard();

    assertThat(schedule.dueAt(1)).isEqualTo(3_592_000L);
    assertThat(schedule.dueAt(2)).isEqualTo(6_184_000L);
    assertThat(schedule.dueAt(3)).isEqualTo(8_776_000L);
  }

  @Test
  void theLastDueTimeIsTheEndOfTheTerm() {
    LoanSchedule schedule = new LoanSchedule(ONE_THOUSAND_TOKENS, 1000, 45, 6, 1_700_000_000L);

    assertThat(schedule.dueAt(6)).isEqualTo(1_700_000_000L + 45L * 86_400L);
  }

  @Test
  void theDueTimeUsesIntegerDivision() {
    LoanSchedule schedule = new LoanSchedule(ONE_THOUSAND_TOKENS, 1000, 10, 3, 0);

    assertThat(schedule.dueAt(1)).isEqualTo(10L * 86_400L / 3L);
    assertThat(schedule.dueAt(2)).isEqualTo(2L * 10L * 86_400L / 3L);
  }

  @Test
  void theNextDueTimeFollowsTheCurrentInstallment() {
    LoanSchedule schedule = standard();

    assertThat(schedule.nextDueAt(BigInteger.ZERO)).isEqualTo(schedule.dueAt(1));
    assertThat(schedule.nextDueAt(big(341_552_511L))).isEqualTo(schedule.dueAt(2));
    assertThat(schedule.nextDueAt(big(683_105_022L))).isEqualTo(schedule.dueAt(3));
  }

  @Test
  void installmentsPaidStaysBelowTheCountUntilTheTotalIsPaid() {
    LoanSchedule schedule = standard();

    assertThat(schedule.installmentsPaid(BigInteger.ZERO)).isZero();
    assertThat(schedule.installmentsPaid(big(341_552_510L))).isZero();
    assertThat(schedule.installmentsPaid(big(341_552_511L))).isEqualTo(1);
    assertThat(schedule.installmentsPaid(big(683_105_022L))).isEqualTo(2);
    assertThat(schedule.installmentsPaid(schedule.totalDue().subtract(BigInteger.ONE)))
        .isEqualTo(2);
    assertThat(schedule.installmentsPaid(schedule.totalDue())).isEqualTo(3);
  }

  @Test
  void aPaymentOneSecondLateCountsAsOneDay() {
    assertThat(LoanSchedule.daysLate(1_001L, 1_000L)).isEqualTo(1);
  }

  @Test
  void aPaymentExactlyOneDayLateCountsAsOneDay() {
    assertThat(LoanSchedule.daysLate(1_000L + 86_400L, 1_000L)).isEqualTo(1);
  }

  @Test
  void aPaymentOneDayAndOneSecondLateCountsAsTwoDays() {
    assertThat(LoanSchedule.daysLate(1_000L + 86_401L, 1_000L)).isEqualTo(2);
  }

  @Test
  void aTimestampBeforeTheDueTimeStillCountsAsOneDay() {
    assertThat(LoanSchedule.daysLate(500L, 1_000L)).isEqualTo(1);
  }

  @Test
  void theConstructorRejectsInvalidShapes() {
    assertThatThrownBy(() -> new LoanSchedule(BigInteger.ONE, 1000, 90, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LoanSchedule(BigInteger.ONE, 1000, 0, 3, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LoanSchedule(big(-1), 1000, 90, 3, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LoanSchedule(null, 1000, 90, 3, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
