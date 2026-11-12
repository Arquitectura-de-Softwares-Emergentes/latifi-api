package com.latifi.api.exchangerate;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.exchangerate.application.RateService;
import com.latifi.api.exchangerate.application.RateService.Converted;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.LatifiIntegrationTest;
import com.latifi.api.support.MutableClock;
import com.latifi.api.support.TestBeansConfig;
import com.latifi.api.support.TestExchangeRateProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Ten requests on an empty cache cause one provider call, and all ten get an answer. */
@LatifiIntegrationTest
class RateServiceConcurrencyTest {

  private static final int THREADS = 10;

  @Autowired private RateService service;
  @Autowired private TestExchangeRateProvider provider;
  @Autowired private DatabaseReset databaseReset;
  @Autowired private MutableClock clock;

  @BeforeEach
  void resetState() {
    databaseReset.run();
    provider.reset();
    service.forgetFailure();
    clock.set(TestBeansConfig.START);
  }

  @AfterEach
  void restoreProvider() {
    provider.reset();
  }

  @Test
  void tenSimultaneousRequestsShareOneRefresh() throws Exception {
    provider.delay(Duration.ofMillis(300));
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CyclicBarrier start = new CyclicBarrier(THREADS);
    try {
      List<Future<Converted>> results = new ArrayList<>();
      for (int i = 0; i < THREADS; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return service.convert("250000000", "PEN");
                }));
      }
      for (Future<Converted> result : results) {
        Converted converted = result.get(10, TimeUnit.SECONDS);
        assertThat(converted.stale()).isFalse();
        assertThat(converted.value()).isEqualByComparingTo("859.51");
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(provider.calls()).isEqualTo(1);
  }
}
