package com.latifi.api.support;

import com.latifi.api.exchangerate.domain.ExchangeRateProvider;
import com.latifi.api.exchangerate.domain.ProviderRates;
import com.latifi.api.exchangerate.domain.RateProviderException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A rate provider that tests control. It replaces the HTTP adapter, so no test reaches the network.
 * It holds rates and a publish time, can fail, can answer slowly and counts its calls.
 */
public class TestExchangeRateProvider implements ExchangeRateProvider {

  /** The rates of 2026-11-09 that the research verified, in units per one US dollar. */
  public static final Map<String, BigDecimal> STANDARD_RATES =
      Map.of(
          "PEN", new BigDecimal("3.438038"),
          "COP", new BigDecimal("3239.854719"),
          "MXN", new BigDecimal("18.652374"),
          "ARS", new BigDecimal("1545.3974"),
          "BRL", new BigDecimal("5.502723"),
          "CLP", new BigDecimal("974.378765"));

  /** The publish time of the standard rates. */
  public static final Instant STANDARD_AS_OF = Instant.parse("2026-01-01T11:00:00Z");

  private final AtomicInteger calls = new AtomicInteger();
  private volatile Map<String, BigDecimal> rates = STANDARD_RATES;
  private volatile Instant asOf = STANDARD_AS_OF;
  private volatile boolean failing;
  private volatile Duration delay = Duration.ZERO;

  @Override
  public ProviderRates fetch(Set<String> currencies) {
    calls.incrementAndGet();
    sleep(delay);
    if (failing) {
      throw new RateProviderException("The test provider is down");
    }
    Map<String, BigDecimal> answer = new HashMap<>();
    for (String currency : currencies) {
      BigDecimal rate = rates.get(currency);
      if (rate == null) {
        throw new RateProviderException("The test provider has no rate of " + currency);
      }
      answer.put(currency, rate);
    }
    return new ProviderRates(asOf, answer);
  }

  /** Sets the rates and the publish time that the next successful calls answer. */
  public void rates(Map<String, BigDecimal> newRates, Instant newAsOf) {
    this.rates = Map.copyOf(newRates);
    this.asOf = newAsOf;
  }

  public void fail(boolean value) {
    this.failing = value;
  }

  public void delay(Duration value) {
    this.delay = value;
  }

  public int calls() {
    return calls.get();
  }

  /** Back to the standard rates, working, instant and with no recorded call. */
  public void reset() {
    rates = STANDARD_RATES;
    asOf = STANDARD_AS_OF;
    failing = false;
    delay = Duration.ZERO;
    calls.set(0);
  }

  private static void sleep(Duration duration) {
    if (duration.isZero()) {
      return;
    }
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RateProviderException("The test provider was interrupted");
    }
  }
}
