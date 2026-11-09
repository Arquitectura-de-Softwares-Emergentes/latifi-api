package com.latifi.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.config.RateLimitProperties;
import com.latifi.api.identity.infrastructure.AuthRateLimiter;
import com.latifi.api.identity.infrastructure.AuthRateLimiter.Decision;
import com.latifi.api.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
  private final AuthRateLimiter limiter =
      new AuthRateLimiter(
          new RateLimitProperties(2, Duration.ofMinutes(1), 2, Duration.ofMinutes(1), false),
          clock);

  @Test
  void anExhaustedClientBucketAsksToWaitAtLeastOneSecond() {
    assertThat(limiter.tryClient("a").allowed()).isTrue();
    assertThat(limiter.tryClient("a").allowed()).isTrue();

    Decision denied = limiter.tryClient("a");

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    assertThat(limiter.tryClient("b").allowed()).isTrue();
  }

  @Test
  void anExhaustedAddressBucketAsksToWaitAtLeastOneSecond() {
    limiter.tryChallengeAddress("0xabc");
    limiter.tryChallengeAddress("0xabc");

    Decision denied = limiter.tryChallengeAddress("0xabc");

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void evictIdleEmptiesBothMapsAfterThePeriod() {
    limiter.tryClient("a");
    limiter.tryChallengeAddress("0xabc");

    clock.advance(Duration.ofSeconds(30));
    limiter.evictIdle();
    assertThat(limiter.clientCount()).isEqualTo(1);
    assertThat(limiter.addressCount()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(31));
    limiter.evictIdle();
    assertThat(limiter.clientCount()).isZero();
    assertThat(limiter.addressCount()).isZero();
  }

  @Test
  void resetClearsEveryBucket() {
    limiter.tryClient("a");
    limiter.tryChallengeAddress("0xabc");

    limiter.reset();

    assertThat(limiter.clientCount()).isZero();
    assertThat(limiter.addressCount()).isZero();
  }
}
