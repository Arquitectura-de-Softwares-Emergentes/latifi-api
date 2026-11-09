package com.latifi.api.bdd;

import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.identity.infrastructure.AuthRateLimiter;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.MutableClock;
import com.latifi.api.support.TestBeansConfig;
import io.cucumber.java.Before;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every scenario starts from empty tables, empty rate limit buckets, the same fixed time and its
 * own client address, so scenarios never share a client bucket.
 */
public class Hooks {

  private final DatabaseReset databaseReset;
  private final MutableClock clock;
  private final AuthRateLimiter limiter;
  private final ApiClient api;

  public Hooks(
      DatabaseReset databaseReset, MutableClock clock, AuthRateLimiter limiter, ApiClient api) {
    this.databaseReset = databaseReset;
    this.clock = clock;
    this.limiter = limiter;
    this.api = api;
  }

  @Before
  public void resetState() {
    databaseReset.run();
    clock.set(TestBeansConfig.START);
    limiter.reset();
    api.withHeader("X-Forwarded-For", randomClientAddress());
  }

  /** A random address from the documentation range 203.0.113.0/24. */
  public static String randomClientAddress() {
    return "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255);
  }
}
