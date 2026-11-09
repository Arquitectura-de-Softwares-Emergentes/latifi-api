package com.latifi.api.bdd;

import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.exchangerate.application.RateService;
import com.latifi.api.identity.infrastructure.AuthRateLimiter;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.FakeChainNode;
import com.latifi.api.support.MutableClock;
import com.latifi.api.support.TestBeansConfig;
import com.latifi.api.support.TestBeansConfig.RecordingListener;
import com.latifi.api.support.TestExchangeRateProvider;
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
  private final FakeChainNode node;
  private final RecordingListener listener;
  private final TestExchangeRateProvider rateProvider;
  private final RateService rateService;

  public Hooks(
      DatabaseReset databaseReset,
      MutableClock clock,
      AuthRateLimiter limiter,
      ApiClient api,
      FakeChainNode node,
      RecordingListener listener,
      TestExchangeRateProvider rateProvider,
      RateService rateService) {
    this.databaseReset = databaseReset;
    this.clock = clock;
    this.limiter = limiter;
    this.api = api;
    this.node = node;
    this.listener = listener;
    this.rateProvider = rateProvider;
    this.rateService = rateService;
  }

  @Before
  public void resetState() {
    databaseReset.run();
    clock.set(TestBeansConfig.START);
    limiter.reset();
    node.reset();
    listener.clear();
    rateProvider.reset();
    rateService.forgetFailure();
    api.withHeader("X-Forwarded-For", randomClientAddress());
  }

  /** A random address from the documentation range 203.0.113.0/24. */
  public static String randomClientAddress() {
    return "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255);
  }
}
