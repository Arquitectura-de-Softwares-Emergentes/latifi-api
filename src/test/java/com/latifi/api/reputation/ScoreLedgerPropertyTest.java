package com.latifi.api.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.reputation.domain.LedgerEntry;
import com.latifi.api.reputation.domain.ReputationPolicy;
import com.latifi.api.reputation.domain.ReputationResult;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.ScoreLedger;
import com.latifi.api.reputation.domain.SignalType;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Properties of the fold over random signal lists. The seed is fixed, so every run repeats. */
class ScoreLedgerPropertyTest {

  private static final long SEED = 20260611L;
  private static final int LISTS = 200;
  private static final int MAX_SIGNALS = 40;
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  private static final ReputationPolicy POLICY = ReputationFixtures.defaultPolicy();

  private static List<List<ReputationSignal>> randomLists(Random random) {
    List<List<ReputationSignal>> lists = new ArrayList<>();
    for (int i = 0; i < LISTS; i++) {
      lists.add(randomList(random));
    }
    return lists;
  }

  private static List<ReputationSignal> randomList(Random random) {
    int size = random.nextInt(MAX_SIGNALS + 1);
    boolean profileUsed = false;
    List<ReputationSignal> signals = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      // A narrow time range on purpose, so many signals tie on the timestamp.
      Instant occurredAt = BASE.plusSeconds(random.nextInt(15));
      SignalType type = SignalType.values()[random.nextInt(SignalType.values().length)];
      if (type == SignalType.PROFILE_COMPLETED) {
        if (profileUsed) {
          continue;
        }
        profileUsed = true;
        signals.add(new ReputationSignal(type, occurredAt, 0, 0, null, null, 0, null, "profile"));
        continue;
      }
      BigInteger principal =
          random.nextBoolean() ? BigInteger.valueOf(5_000_000L) : BigInteger.valueOf(400_000L);
      int daysLate = type == SignalType.REPAID_LATE ? 1 + random.nextInt(40) : 0;
      String hash = "0x" + String.format("%064x", i);
      signals.add(
          new ReputationSignal(
              type,
              occurredAt,
              100L + random.nextInt(5),
              random.nextInt(4),
              "loan-" + i,
              principal,
              daysLate,
              hash,
              "tx:" + hash + ":" + i));
    }
    return signals;
  }

  @Test
  void theScoreStaysInsideTheRange() {
    for (List<ReputationSignal> signals : randomLists(new Random(SEED))) {
      ReputationResult result = ScoreLedger.fold(POLICY, signals);

      assertThat(result.score()).isBetween(0, 1000);
      assertThat(result.entries()).allSatisfy(e -> assertThat(e.scoreAfter()).isBetween(0, 1000));
    }
  }

  @Test
  void everyEntryAddsItsDeltaToThePreviousScore() {
    for (List<ReputationSignal> signals : randomLists(new Random(SEED))) {
      ReputationResult result = ScoreLedger.fold(POLICY, signals);

      int previous = POLICY.startScore();
      for (LedgerEntry entry : result.entries()) {
        assertThat(entry.scoreAfter()).isEqualTo(previous + entry.delta());
        previous = entry.scoreAfter();
      }
      assertThat(result.score()).isEqualTo(previous);
    }
  }

  @Test
  void foldingEveryElementTwiceEqualsFoldingOnce() {
    for (List<ReputationSignal> signals : randomLists(new Random(SEED))) {
      List<ReputationSignal> doubled = new ArrayList<>(signals);
      doubled.addAll(signals);

      assertThat(ScoreLedger.fold(POLICY, doubled)).isEqualTo(ScoreLedger.fold(POLICY, signals));
    }
  }

  @Test
  void tenRandomPermutationsGiveTheSameResult() {
    Random random = new Random(SEED);
    for (List<ReputationSignal> signals : randomLists(random)) {
      ReputationResult expected = ScoreLedger.fold(POLICY, signals);
      for (int round = 0; round < 10; round++) {
        List<ReputationSignal> shuffled = new ArrayList<>(signals);
        Collections.shuffle(shuffled, random);

        assertThat(ScoreLedger.fold(POLICY, shuffled)).isEqualTo(expected);
      }
    }
  }

  @Test
  void theEntriesOfTheFirstHalfAreAPrefixOfTheFullLedger() {
    for (List<ReputationSignal> signals : randomLists(new Random(SEED))) {
      List<ReputationSignal> sorted = new ArrayList<>(signals);
      sorted.sort(ScoreLedger.TOTAL_ORDER);
      List<ReputationSignal> firstHalf = sorted.subList(0, sorted.size() / 2);

      ReputationResult full = ScoreLedger.fold(POLICY, sorted);
      ReputationResult half = ScoreLedger.fold(POLICY, firstHalf);

      assertThat(full.entries().subList(0, half.entries().size())).isEqualTo(half.entries());
    }
  }

  @Test
  void theGeneratorProducesEveryTypeAndBothSidesOfTheMinimumPrincipal() {
    List<ReputationSignal> all = new ArrayList<>();
    randomLists(new Random(SEED)).forEach(all::addAll);

    assertThat(all).extracting(ReputationSignal::type).contains(SignalType.values());
    assertThat(all)
        .filteredOn(s -> s.principal() != null)
        .extracting(s -> s.principal().compareTo(POLICY.minScoredPrincipal()) < 0)
        .contains(true, false);
  }
}
