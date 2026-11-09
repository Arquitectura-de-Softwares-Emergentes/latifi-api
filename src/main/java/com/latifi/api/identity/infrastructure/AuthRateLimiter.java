package com.latifi.api.identity.infrastructure;

import com.latifi.api.config.RateLimitProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Token buckets for the auth endpoints, one map per client and one per wallet address. Idle entries
 * disappear on a schedule: a bucket that has been quiet for a whole period is full again, so
 * dropping it changes nothing for the caller.
 */
@Component
public class AuthRateLimiter {

  /** The answer of a limit check. {@code retryAfterSeconds} is 0 when the call is allowed. */
  public record Decision(boolean allowed, long retryAfterSeconds) {}

  private static final class Entry {
    private final Bucket bucket;
    private volatile Instant lastAccess;

    Entry(Bucket bucket, Instant lastAccess) {
      this.bucket = bucket;
      this.lastAccess = lastAccess;
    }
  }

  private final Clock clock;
  private final RateLimitProperties properties;
  private final Map<String, Entry> clients = new ConcurrentHashMap<>();
  private final Map<String, Entry> addresses = new ConcurrentHashMap<>();

  public AuthRateLimiter(RateLimitProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public Decision tryClient(String key) {
    return consume(clients, key, properties.authPerIpCapacity(), properties.authPerIpPeriod());
  }

  public Decision tryChallengeAddress(String address) {
    return consume(
        addresses,
        address,
        properties.challengePerAddressCapacity(),
        properties.challengePerAddressPeriod());
  }

  private Decision consume(Map<String, Entry> entries, String key, long capacity, Duration period) {
    Instant now = clock.instant();
    Entry entry = entries.computeIfAbsent(key, k -> new Entry(newBucket(capacity, period), now));
    entry.lastAccess = now;
    ConsumptionProbe probe = entry.bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return new Decision(true, 0);
    }
    long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
    boolean hasFraction = probe.getNanosToWaitForRefill() % TimeUnit.SECONDS.toNanos(1) != 0;
    return new Decision(false, Math.max(1, hasFraction ? seconds + 1 : seconds));
  }

  private static Bucket newBucket(long capacity, Duration period) {
    return Bucket.builder()
        .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, period))
        .build();
  }

  /** Removes entries that nobody touched for longer than their period. Runs every minute. */
  @Scheduled(fixedDelay = 60_000)
  public void evictIdle() {
    Instant now = clock.instant();
    evict(clients, now, properties.authPerIpPeriod());
    evict(addresses, now, properties.challengePerAddressPeriod());
  }

  private static void evict(Map<String, Entry> entries, Instant now, Duration period) {
    entries.values().removeIf(entry -> entry.lastAccess.plus(period).isBefore(now));
  }

  /** Clears every bucket. Tests call it before each scenario. */
  public void reset() {
    clients.clear();
    addresses.clear();
  }

  /** Number of tracked clients, for tests. */
  public int clientCount() {
    return clients.size();
  }

  /** Number of tracked addresses, for tests. */
  public int addressCount() {
    return addresses.size();
  }
}
