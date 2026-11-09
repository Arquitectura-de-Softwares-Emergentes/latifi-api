package com.latifi.api.reputation;

import static com.latifi.api.reputation.ReputationFixtures.onTime;
import static com.latifi.api.reputation.ReputationFixtures.profile;
import static com.latifi.api.reputation.ReputationFixtures.withStart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latifi.api.reputation.domain.ReputationLevel;
import com.latifi.api.reputation.domain.ReputationResult;
import com.latifi.api.reputation.domain.ScoreLedger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReputationLevelTest {

  @Test
  void theThresholdsFollowTheReportScaleTimesTen() {
    assertThat(ReputationLevel.ofScore(0)).isEqualTo(ReputationLevel.NEW);
    assertThat(ReputationLevel.ofScore(399)).isEqualTo(ReputationLevel.NEW);
    assertThat(ReputationLevel.ofScore(400)).isEqualTo(ReputationLevel.BUILDING);
    assertThat(ReputationLevel.ofScore(599)).isEqualTo(ReputationLevel.BUILDING);
    assertThat(ReputationLevel.ofScore(600)).isEqualTo(ReputationLevel.TRUSTED);
    assertThat(ReputationLevel.ofScore(799)).isEqualTo(ReputationLevel.TRUSTED);
    assertThat(ReputationLevel.ofScore(800)).isEqualTo(ReputationLevel.EXCELLENT);
    assertThat(ReputationLevel.ofScore(1000)).isEqualTo(ReputationLevel.EXCELLENT);
  }

  @Test
  void aScoreOutsideTheRangeHasNoLevel() {
    assertThatThrownBy(() -> ReputationLevel.ofScore(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReputationLevel.ofScore(1001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ranksRunFromZeroToThreeAndRoundTrip() {
    for (ReputationLevel level : ReputationLevel.values()) {
      assertThat(ReputationLevel.fromRank(level.rank())).isEqualTo(level);
    }
    assertThat(ReputationLevel.NEW.rank()).isZero();
    assertThat(ReputationLevel.EXCELLENT.rank()).isEqualTo(3);
    assertThatThrownBy(() -> ReputationLevel.fromRank(4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReputationLevel.fromRank(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aChainSignalPutsTheLevelOnTheThresholdOfItsScore() {
    assertThat(levelAfterOneOnTimeFrom(379)).isEqualTo(ReputationLevel.NEW);
    assertThat(levelAfterOneOnTimeFrom(380)).isEqualTo(ReputationLevel.BUILDING);
    assertThat(levelAfterOneOnTimeFrom(579)).isEqualTo(ReputationLevel.BUILDING);
    assertThat(levelAfterOneOnTimeFrom(580)).isEqualTo(ReputationLevel.TRUSTED);
    assertThat(levelAfterOneOnTimeFrom(779)).isEqualTo(ReputationLevel.TRUSTED);
    assertThat(levelAfterOneOnTimeFrom(780)).isEqualTo(ReputationLevel.EXCELLENT);
  }

  @Test
  void aLedgerWithOnlyTheProfileStaysNewWhateverTheScore() {
    ReputationResult result = ScoreLedger.fold(withStart(850), List.of(profile(1)));

    assertThat(result.score()).isEqualTo(900);
    assertThat(result.level()).isEqualTo(ReputationLevel.NEW);
    assertThat(result.profileComplete()).isTrue();
  }

  @Test
  void aFreshAddressWithACompleteProfileIsNewAt550() {
    ReputationResult result = ScoreLedger.fold(withStart(500), List.of(profile(1)));

    assertThat(result.score()).isEqualTo(550);
    assertThat(result.level()).isEqualTo(ReputationLevel.NEW);
  }

  private static ReputationLevel levelAfterOneOnTimeFrom(int startScore) {
    return ScoreLedger.fold(withStart(startScore), List.of(onTime(1))).level();
  }
}
