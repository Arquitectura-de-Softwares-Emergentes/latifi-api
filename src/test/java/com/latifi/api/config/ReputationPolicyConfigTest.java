package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.reputation.domain.ReputationPolicy;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReputationPolicyConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ReputationConfig.class);

  @Test
  void withNoPropertyThePolicyHasTheDefaultsOfTheWriteUp() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ReputationPolicy.class))
              .isEqualTo(
                  new ReputationPolicy(500, 20, 5, 100, 200, 50, BigInteger.valueOf(1_000_000L)));
        });
  }

  @Test
  void theOverridesReachThePolicy() {
    runner
        .withPropertyValues(
            "latifi.reputation.start-score=400",
            "latifi.reputation.on-time-delta=30",
            "latifi.reputation.late-per-day=7",
            "latifi.reputation.late-cap=140",
            "latifi.reputation.default-delta=250",
            "latifi.reputation.profile-bonus=60",
            "latifi.reputation.min-scored-principal=2500000")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ReputationPolicy.class))
                  .isEqualTo(
                      new ReputationPolicy(
                          400, 30, 7, 140, 250, 60, BigInteger.valueOf(2_500_000L)));
            });
  }

  @Test
  void aStartScoreAboveTheCeilingStopsTheContext() {
    runner
        .withPropertyValues("latifi.reputation.start-score=2000")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aNegativeCapStopsTheContext() {
    runner
        .withPropertyValues("latifi.reputation.late-cap=-1")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aNegativeMinimumPrincipalStopsTheContext() {
    runner
        .withPropertyValues("latifi.reputation.min-scored-principal=-5")
        .run(context -> assertThat(context).hasFailed());
  }
}
