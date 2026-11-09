package com.latifi.api.reputation;

import static com.latifi.api.reputation.ReputationFixtures.BIG_PRINCIPAL;
import static com.latifi.api.reputation.ReputationFixtures.MIN_PRINCIPAL;
import static com.latifi.api.reputation.ReputationFixtures.defaultPolicy;
import static com.latifi.api.reputation.ReputationFixtures.defaulted;
import static com.latifi.api.reputation.ReputationFixtures.late;
import static com.latifi.api.reputation.ReputationFixtures.onTime;
import static com.latifi.api.reputation.ReputationFixtures.profile;
import static com.latifi.api.reputation.ReputationFixtures.withStart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latifi.api.reputation.domain.LedgerEntry;
import com.latifi.api.reputation.domain.ReputationLevel;
import com.latifi.api.reputation.domain.ReputationPolicy;
import com.latifi.api.reputation.domain.ReputationResult;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.ScoreLedger;
import com.latifi.api.reputation.domain.SignalType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreLedgerTest {

  private static ReputationResult fold(ReputationSignal... signals) {
    return ScoreLedger.fold(defaultPolicy(), List.of(signals));
  }

  private static List<Integer> deltas(ReputationResult result) {
    return result.entries().stream().map(LedgerEntry::delta).toList();
  }

  @Test
  void anEmptyLedgerKeepsTheStartScore() {
    ReputationResult result = ScoreLedger.fold(defaultPolicy(), List.of());

    assertThat(result.score()).isEqualTo(500);
    assertThat(result.level()).isEqualTo(ReputationLevel.NEW);
    assertThat(result.onTimeCount()).isZero();
    assertThat(result.lateCount()).isZero();
    assertThat(result.defaultCount()).isZero();
    assertThat(result.profileComplete()).isFalse();
    assertThat(result.entries()).isEmpty();
  }

  @Test
  void exampleAOfTheWriteUpEndsAt575() {
    ReputationResult result = fold(profile(1), onTime(2), late(3, 3), onTime(4));

    assertThat(deltas(result)).containsExactly(50, 20, -15, 20);
    assertThat(result.score()).isEqualTo(575);
  }

  @Test
  void aDefaultAfterExampleALeavesTheScoreAt375() {
    ReputationResult result = fold(profile(1), onTime(2), late(3, 3), onTime(4), defaulted(5));

    assertThat(result.score()).isEqualTo(375);
    assertThat(result.defaultCount()).isEqualTo(1);
  }

  @Test
  void tenOnTimeInstallmentsRecoverFromADefault() {
    List<ReputationSignal> signals =
        new ArrayList<>(List.of(profile(1), onTime(2), late(3, 3), onTime(4), defaulted(5)));
    for (int i = 0; i < 10; i++) {
      signals.add(onTime(6 + i));
    }

    ReputationResult result = ScoreLedger.fold(defaultPolicy(), signals);

    assertThat(result.score()).isEqualTo(575);
    assertThat(result.onTimeCount()).isEqualTo(12);
  }

  @Test
  void theLatePenaltyGrowsByFivePerDay() {
    assertThat(deltas(fold(late(1, 1)))).containsExactly(-5);
    assertThat(deltas(fold(late(1, 7)))).containsExactly(-35);
  }

  @Test
  void theLatePenaltyStopsAtTheCap() {
    assertThat(deltas(fold(late(1, 20)))).containsExactly(-100);
    assertThat(deltas(fold(late(1, 30)))).containsExactly(-100);
    assertThat(deltas(fold(late(1, 40)))).containsExactly(-100);
  }

  @Test
  void aLateSignalWithZeroDaysStillCountsAsOneDay() {
    assertThat(deltas(fold(late(1, 0)))).containsExactly(-5);
  }

  @Test
  void anOnTimeSignalRecordsItsDeltaWithZeroDaysLate() {
    ReputationResult result = fold(onTime(1));

    assertThat(result.entries().get(0).signal().daysLate()).isZero();
    assertThat(deltas(result)).containsExactly(20);
  }

  @Test
  void theCeilingClampsTheEffectiveDelta() {
    ReputationResult result = ScoreLedger.fold(withStart(990), List.of(onTime(1), onTime(2)));

    assertThat(deltas(result)).containsExactly(10, 0);
    assertThat(result.score()).isEqualTo(1000);
  }

  @Test
  void theFloorClampsTheEffectiveDelta() {
    ReputationResult result = ScoreLedger.fold(withStart(100), List.of(defaulted(1), defaulted(2)));

    assertThat(deltas(result)).containsExactly(-100, 0);
    assertThat(result.score()).isZero();
  }

  @Test
  void exampleBOfTheWriteUpKeepsTheCapAfterTheCeiling() {
    ReputationResult result =
        ScoreLedger.fold(withStart(990), List.of(onTime(1), onTime(2), late(3, 30)));

    assertThat(deltas(result)).containsExactly(10, 0, -100);
    assertThat(result.score()).isEqualTo(900);
  }

  @Test
  void everyEntryHoldsScoreAfterEqualToScoreBeforePlusDelta() {
    ReputationResult result =
        ScoreLedger.fold(
            withStart(990),
            List.of(onTime(1), late(2, 30), defaulted(3), defaulted(4), defaulted(5), onTime(6)));

    int before = 990;
    for (LedgerEntry entry : result.entries()) {
      assertThat(entry.scoreAfter()).isEqualTo(before + entry.delta());
      before = entry.scoreAfter();
    }
    assertThat(result.score()).isEqualTo(before);
  }

  @Test
  void entriesNumberFromOne() {
    ReputationResult result = fold(profile(1), onTime(2), onTime(3));

    assertThat(result.entries()).extracting(LedgerEntry::seq).containsExactly(1, 2, 3);
  }

  @Test
  void anOnTimeSignalBelowTheMinimumPrincipalCreatesNoEntry() {
    BigInteger dust = MIN_PRINCIPAL.subtract(BigInteger.ONE);

    ReputationResult result = fold(onTime(1, dust));

    assertThat(result.entries()).isEmpty();
    assertThat(result.score()).isEqualTo(500);
    assertThat(result.onTimeCount()).isZero();
    assertThat(result.level()).isEqualTo(ReputationLevel.NEW);
  }

  @Test
  void aLateSignalOfASmallLoanStillCounts() {
    BigInteger dust = BigInteger.ONE;

    ReputationResult result = fold(late(1, 2, dust));

    assertThat(deltas(result)).containsExactly(-10);
    assertThat(result.lateCount()).isEqualTo(1);
  }

  @Test
  void aDefaultOfASmallLoanStillCounts() {
    ReputationResult result = fold(defaulted(1, BigInteger.ONE));

    assertThat(deltas(result)).containsExactly(-200);
    assertThat(result.defaultCount()).isEqualTo(1);
  }

  @Test
  void aPrincipalExactlyAtTheMinimumIsScored() {
    ReputationResult result = fold(onTime(1, MIN_PRINCIPAL));

    assertThat(deltas(result)).containsExactly(20);
    assertThat(result.onTimeCount()).isEqualTo(1);
  }

  @Test
  void aSkippedDustSignalIsNotCountedAndTheScoredOneIs() {
    ReputationResult result = fold(onTime(1, BigInteger.ONE), onTime(2, BIG_PRINCIPAL));

    assertThat(result.onTimeCount()).isEqualTo(1);
    assertThat(result.entries()).hasSize(1);
  }

  @Test
  void theSameSourceKeyCountsOnce() {
    ReputationSignal signal = onTime(1);

    ReputationResult result = fold(signal, signal);

    assertThat(result.entries()).hasSize(1);
    assertThat(result.score()).isEqualTo(520);
  }

  @Test
  void aProfileSignalAddsTheBonusAndFlagsTheProfile() {
    ReputationResult result = fold(profile(1));

    assertThat(result.score()).isEqualTo(550);
    assertThat(result.profileComplete()).isTrue();
    assertThat(result.level()).isEqualTo(ReputationLevel.NEW);
  }

  @Test
  void theProfileIsNotCompleteWithoutItsEntry() {
    assertThat(fold(onTime(1)).profileComplete()).isFalse();
  }

  @Test
  void countersCountEntriesByType() {
    ReputationResult result =
        fold(profile(1), onTime(2), onTime(3), late(4, 1), defaulted(5), defaulted(6));

    assertThat(result.onTimeCount()).isEqualTo(2);
    assertThat(result.lateCount()).isEqualTo(1);
    assertThat(result.defaultCount()).isEqualTo(2);
  }

  @Test
  void theInputOrderDoesNotChangeTheResult() {
    List<ReputationSignal> signals =
        new ArrayList<>(
            List.of(profile(1), onTime(2), late(3, 3), onTime(4), defaulted(5), onTime(6)));
    ReputationResult expected = ScoreLedger.fold(defaultPolicy(), signals);

    for (int round = 0; round < 6; round++) {
      Collections.rotate(signals, 1);
      assertThat(ScoreLedger.fold(defaultPolicy(), signals)).isEqualTo(expected);
    }
    Collections.reverse(signals);
    assertThat(ScoreLedger.fold(defaultPolicy(), signals)).isEqualTo(expected);
  }

  @Test
  void theProfileComesBeforeAChainEventAtTheSameTimestamp() {
    ReputationSignal chainEvent = onTime(5);
    ReputationSignal profile =
        new ReputationSignal(
            SignalType.PROFILE_COMPLETED,
            chainEvent.occurredAt(),
            0,
            0,
            null,
            null,
            0,
            null,
            "profile");

    ReputationResult result = fold(chainEvent, profile);

    assertThat(result.entries().get(0).signal().type()).isEqualTo(SignalType.PROFILE_COMPLETED);
    assertThat(profile.sourceRank()).isZero();
    assertThat(chainEvent.sourceRank()).isEqualTo(1);
  }

  @Test
  void blockNumberAndLogIndexBreakTiesAtTheSameTimestamp() {
    ReputationSignal first = withPosition(SignalType.REPAID_ON_TIME, 10, 0, "tx:a:0");
    ReputationSignal second = withPosition(SignalType.REPAID_ON_TIME, 10, 3, "tx:b:3");
    ReputationSignal third = withPosition(SignalType.REPAID_ON_TIME, 11, 0, "tx:c:0");

    ReputationResult result = fold(third, second, first);

    assertThat(result.entries())
        .extracting(entry -> entry.signal().sourceKey())
        .containsExactly("tx:a:0", "tx:b:3", "tx:c:0");
  }

  @Test
  void theSourceKeyIsTheLastTieBreak() {
    ReputationSignal a = withPosition(SignalType.REPAID_ON_TIME, 10, 0, "tx:a:0");
    ReputationSignal b = withPosition(SignalType.REPAID_ON_TIME, 10, 0, "tx:b:0");

    assertThat(fold(b, a).entries())
        .extracting(entry -> entry.signal().sourceKey())
        .containsExactly("tx:a:0", "tx:b:0");
  }

  @Test
  void aPrefixOfTheOrderedSignalsGivesAPrefixOfTheEntries() {
    List<ReputationSignal> ordered =
        List.of(profile(1), onTime(2), late(3, 3), onTime(4), defaulted(5), onTime(6));
    ReputationResult full = ScoreLedger.fold(defaultPolicy(), ordered);

    for (int k = 0; k <= ordered.size(); k++) {
      ReputationResult prefix = ScoreLedger.fold(defaultPolicy(), ordered.subList(0, k));
      assertThat(full.entries().subList(0, prefix.entries().size())).isEqualTo(prefix.entries());
    }
  }

  @Test
  void aLedgerWithOnlyAChainSignalGetsALevelFromItsScore() {
    ReputationResult result = fold(onTime(1), onTime(2), onTime(3), onTime(4), onTime(5));

    assertThat(result.score()).isEqualTo(600);
    assertThat(result.level()).isEqualTo(ReputationLevel.TRUSTED);
  }

  @Test
  void thePolicyRejectsAStartScoreOutsideTheRange() {
    assertThatThrownBy(() -> withStart(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withStart(1001)).isInstanceOf(IllegalArgumentException.class);
    assertThat(withStart(0).startScore()).isZero();
    assertThat(withStart(1000).startScore()).isEqualTo(1000);
  }

  @Test
  void thePolicyRejectsNegativeDeltasCapsAndMinimums() {
    BigInteger min = MIN_PRINCIPAL;
    assertThatThrownBy(() -> new ReputationPolicy(500, -1, 5, 100, 200, 50, min))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReputationPolicy(500, 20, -1, 100, 200, 50, min))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReputationPolicy(500, 20, 5, -1, 200, 50, min))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReputationPolicy(500, 20, 5, 100, -1, 50, min))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReputationPolicy(500, 20, 5, 100, 200, -1, min))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReputationPolicy(500, 20, 5, 100, 200, 50, BigInteger.valueOf(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReputationPolicy(500, 20, 5, 100, 200, 50, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ReputationSignal withPosition(
      SignalType type, long blockNumber, int logIndex, String sourceKey) {
    return new ReputationSignal(
        type,
        ReputationFixtures.at(100),
        blockNumber,
        logIndex,
        "loan",
        BIG_PRINCIPAL,
        0,
        "0xabc",
        sourceKey);
  }
}
