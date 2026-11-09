package com.latifi.api.exchangerate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latifi.api.config.FxProperties;
import com.latifi.api.exchangerate.application.RateService;
import com.latifi.api.exchangerate.domain.CachedRate;
import com.latifi.api.exchangerate.domain.ProviderRates;
import com.latifi.api.exchangerate.domain.RateCache;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import com.latifi.api.support.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * A refresh that fails for a reason other than the provider, such as a database error, serves the
 * cache and starts the backoff like any failed refresh.
 */
class RateServiceRefreshFailureTest {

  private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

  /** A cache in memory whose writes and reads can be made to fail. */
  private static final class FlakyCache implements RateCache {
    private final List<CachedRate> rows = new ArrayList<>();
    private boolean failWrites;
    private int readsBeforeFailure = -1;

    @Override
    public List<CachedRate> readAll() {
      if (readsBeforeFailure == 0) {
        throw new DataAccessResourceFailureException("connection lost");
      }
      if (readsBeforeFailure > 0) {
        readsBeforeFailure--;
      }
      return List.copyOf(rows);
    }

    @Override
    public void replaceAll(ProviderRates rates, Instant fetchedAt) {
      if (failWrites) {
        throw new DataAccessResourceFailureException("connection lost");
      }
      rows.clear();
      rates
          .rates()
          .forEach((code, rate) -> rows.add(new CachedRate(code, rate, rates.asOf(), fetchedAt)));
    }
  }

  private final MutableClock clock = new MutableClock(START);
  private final FlakyCache cache = new FlakyCache();
  private final AtomicInteger providerCalls = new AtomicInteger();
  private RateService service;

  @BeforeEach
  void build() {
    FxProperties properties =
        new FxProperties(
            "https://rates.example/latest",
            "",
            List.of("PEN", "COP"),
            Duration.ofHours(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofMinutes(1));
    service =
        new RateService(
            currencies -> {
              providerCalls.incrementAndGet();
              return new ProviderRates(
                  clock.instant(),
                  Map.of("PEN", new BigDecimal("3.70"), "COP", new BigDecimal("4100")));
            },
            cache,
            properties,
            clock);
  }

  private void seedExpiredCache() {
    cache.replaceAll(
        new ProviderRates(
            START, Map.of("PEN", new BigDecimal("3.50"), "COP", new BigDecimal("4000"))),
        START);
    clock.set(START.plus(Duration.ofHours(2)));
  }

  @Test
  void aDatabaseErrorWhileStoringServesTheStaleCache() {
    seedExpiredCache();
    cache.failWrites = true;

    RateService.Quote quote = service.quote("PEN");

    assertThat(quote.stale()).isTrue();
    assertThat(quote.rate()).isEqualByComparingTo("3.50");
  }

  @Test
  void aDatabaseErrorWhileStoringStartsTheBackoff() {
    seedExpiredCache();
    cache.failWrites = true;

    service.quote("PEN");
    service.quote("COP");
    service.quote("PEN");

    assertThat(providerCalls.get()).isEqualTo(1);
  }

  @Test
  void aDatabaseErrorWhileReadingBackServesTheStaleCache() {
    seedExpiredCache();
    // The first two reads are the checks before the lock, the third is the read after the refresh.
    cache.readsBeforeFailure = 2;

    RateService.Quote quote = service.quote("PEN");

    assertThat(quote.stale()).isTrue();
    assertThat(quote.rate()).isEqualByComparingTo("3.50");
  }

  @Test
  void withNoCacheAtAllTheAnswerIsStillRateUnavailable() {
    cache.failWrites = true;

    assertThatThrownBy(() -> service.quote("PEN"))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ApiErrorCode.RATE_UNAVAILABLE);
  }
}
