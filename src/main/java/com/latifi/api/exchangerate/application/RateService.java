package com.latifi.api.exchangerate.application;

import com.latifi.api.config.FxProperties;
import com.latifi.api.exchangerate.domain.CachedRate;
import com.latifi.api.exchangerate.domain.Conversion;
import com.latifi.api.exchangerate.domain.ExchangeRateProvider;
import com.latifi.api.exchangerate.domain.ProviderRates;
import com.latifi.api.exchangerate.domain.RateCache;
import com.latifi.api.exchangerate.domain.RateProviderException;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import com.latifi.api.shared.Uint256;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Serves exchange rates from the cache and refreshes them from the provider.
 *
 * <p>A cache younger than the TTL answers without a provider call. An older one refreshes under a
 * lock, so concurrent requests cause one call, and the call runs outside any database transaction.
 * When the refresh fails, for any reason including a database error, the last cached rates are
 * served flagged stale, and the provider is not called again before the retry backoff has passed.
 * With no cached rate at all the answer is 503 RATE_UNAVAILABLE. This class is deliberately not
 * transactional.
 */
@Service
public class RateService {

  private static final Logger LOG = LoggerFactory.getLogger(RateService.class);
  private static final Pattern CURRENCY_FORMAT = Pattern.compile("^[A-Z]{3}$");

  /** One rate as the API shows it. */
  public record Quote(String currency, BigDecimal rate, Instant asOf, boolean stale) {}

  /** A converted amount. */
  public record Converted(
      BigInteger amount,
      String currency,
      BigDecimal value,
      BigDecimal rate,
      Instant asOf,
      boolean stale) {}

  /** The cached rates of the supported currencies and whether they come from a failed refresh. */
  private record Snapshot(Map<String, CachedRate> rates, boolean stale) {}

  private final ExchangeRateProvider provider;
  private final RateCache cache;
  private final FxProperties properties;
  private final Clock clock;
  private final Set<String> supported;
  private final ReentrantLock refreshLock = new ReentrantLock();

  /** Instant of the last failed refresh, or null after a success. Guarded by the lock to write. */
  private volatile Instant lastFailure;

  public RateService(
      ExchangeRateProvider provider, RateCache cache, FxProperties properties, Clock clock) {
    this.provider = provider;
    this.cache = cache;
    this.properties = properties;
    this.clock = clock;
    this.supported = Set.copyOf(properties.supported());
  }

  /** The rate of one currency. */
  public Quote quote(String currency) {
    requireSupported(currency);
    Snapshot snapshot = snapshot();
    return quoteOf(snapshot, currency);
  }

  /** The rates of every supported currency, in the order of the configuration. */
  public List<Quote> list() {
    Snapshot snapshot = snapshot();
    return properties.supported().stream().map(code -> quoteOf(snapshot, code)).toList();
  }

  /**
   * Converts an amount of stablecoin base units. The amount must be a uint256 written in digits and
   * the currency must be supported.
   */
  public Converted convert(String amountText, String currency) {
    requireFormat(currency);
    BigInteger amount = Uint256.parse(amountText);
    requireSupported(currency);
    Quote quote = quoteOf(snapshot(), currency);
    BigDecimal value = Conversion.convert(amount, quote.rate(), currency);
    return new Converted(amount, currency, value, quote.rate(), quote.asOf(), quote.stale());
  }

  /**
   * Forgets the last failed refresh, so the next request may call the provider at once. Tests call
   * this between scenarios because they also reset the clock.
   */
  public void forgetFailure() {
    lastFailure = null;
  }

  private void requireFormat(String currency) {
    if (currency == null || !CURRENCY_FORMAT.matcher(currency).matches()) {
      throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
  }

  private void requireSupported(String currency) {
    requireFormat(currency);
    if (!supported.contains(currency)) {
      throw new ApiException(ApiErrorCode.UNSUPPORTED_CURRENCY);
    }
  }

  private Quote quoteOf(Snapshot snapshot, String currency) {
    CachedRate row = snapshot.rates().get(currency);
    if (row == null) {
      throw new ApiException(ApiErrorCode.RATE_UNAVAILABLE);
    }
    return new Quote(currency, row.rate(), row.asOf(), snapshot.stale());
  }

  private Snapshot snapshot() {
    Map<String, CachedRate> rows = read();
    if (isFresh(rows, clock.instant())) {
      return new Snapshot(rows, false);
    }
    if (inBackoff(clock.instant())) {
      return fallback(rows);
    }
    refreshLock.lock();
    try {
      // Another request may have refreshed while this one waited for the lock.
      rows = read();
      Instant now = clock.instant();
      if (isFresh(rows, now)) {
        return new Snapshot(rows, false);
      }
      if (inBackoff(now)) {
        return fallback(rows);
      }
      return refresh(now, rows);
    } finally {
      refreshLock.unlock();
    }
  }

  private Snapshot refresh(Instant now, Map<String, CachedRate> previous) {
    try {
      ProviderRates fetched = provider.fetch(supported);
      cache.replaceAll(fetched, clock.instant());
      lastFailure = null;
      return new Snapshot(read(), false);
    } catch (RateProviderException e) {
      lastFailure = now;
      LOG.warn(
          "The exchange rate refresh failed, serving the cache if there is one: {}",
          e.getMessage());
      return fallback(previous);
    } catch (RuntimeException e) {
      // The provider answered or not, but storing or reading the rates failed, for example with a
      // database error. It counts as a failed refresh: the backoff starts, so one database fault
      // does not turn into a burst of provider calls, and the stale cache is served. Only the
      // class name is logged, because a database message can quote the connection.
      lastFailure = now;
      LOG.warn(
          "The exchange rate refresh failed after the provider call, serving the cache if there is"
              + " one: {}",
          e.getClass().getSimpleName());
      return fallback(previous);
    }
  }

  private Snapshot fallback(Map<String, CachedRate> rows) {
    if (rows.isEmpty()) {
      throw new ApiException(ApiErrorCode.RATE_UNAVAILABLE);
    }
    return new Snapshot(rows, true);
  }

  private boolean inBackoff(Instant now) {
    Instant failure = lastFailure;
    return failure != null
        && Duration.between(failure, now).compareTo(properties.retryBackoff()) < 0;
  }

  /** Fresh means every supported currency is cached and younger than the TTL. */
  private boolean isFresh(Map<String, CachedRate> rows, Instant now) {
    if (!rows.keySet().containsAll(supported)) {
      return false;
    }
    return rows.values().stream()
        .allMatch(
            row -> Duration.between(row.fetchedAt(), now).compareTo(properties.cacheTtl()) < 0);
  }

  private Map<String, CachedRate> read() {
    Map<String, CachedRate> rows = new LinkedHashMap<>();
    for (CachedRate row : cache.readAll()) {
      if (supported.contains(row.currency())) {
        rows.put(row.currency(), row);
      }
    }
    return rows;
  }
}
