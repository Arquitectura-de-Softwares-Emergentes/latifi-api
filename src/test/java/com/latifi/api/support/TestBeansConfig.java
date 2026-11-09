package com.latifi.api.support;

import com.latifi.api.identity.domain.ProfileSaved;
import com.latifi.api.ledger.domain.BatchDelta;
import com.latifi.api.ledger.domain.ChainEventListener;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Beans that replace production ones in tests. Later plans add their own here. */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeansConfig {

  public static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

  /** The token secret of this JVM. It is random at run time and never written to disk. */
  public static final String JWT_SECRET = randomSecret();

  /** Creates a random secret of 48 bytes as text, for tests that need a second key. */
  public static String randomSecret() {
    byte[] bytes = new byte[48];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock(START);
  }

  /** Replaces the HTTP adapter, so no scenario and no test reaches a real rate provider. */
  @Bean
  @Primary
  TestExchangeRateProvider testExchangeRateProvider() {
    return new TestExchangeRateProvider();
  }

  @Bean
  DynamicPropertyRegistrar jwtSecretRegistrar() {
    return registry -> {
      registry.add("latifi.jwt.secret", () -> JWT_SECRET);
      registry.add("latifi.ratelimit.trust-forwarded-for", () -> "true");
      registry.add("latifi.ratelimit.auth-per-ip-capacity", () -> "10");
      registry.add("latifi.ratelimit.challenge-per-address-capacity", () -> "5");
    };
  }

  @Bean
  RecordingListener recordingListener() {
    return new RecordingListener();
  }

  /** Keeps every batch that the indexer applied, so scenarios can check the listener seam. */
  public static class RecordingListener implements ChainEventListener {

    private final List<BatchDelta> deltas = new CopyOnWriteArrayList<>();

    @Override
    public void onBatchApplied(BatchDelta delta) {
      deltas.add(delta);
    }

    public List<BatchDelta> deltas() {
      return List.copyOf(deltas);
    }

    public void clear() {
      deltas.clear();
    }
  }

  @Bean
  ProfileEventCollector profileEventCollector() {
    return new ProfileEventCollector();
  }

  /** Keeps every ProfileSaved event, so scenarios can check what the reputation seam receives. */
  public static class ProfileEventCollector {

    private final List<ProfileSaved> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(ProfileSaved event) {
      events.add(event);
    }

    public List<ProfileSaved> events() {
      return List.copyOf(events);
    }

    public void clear() {
      events.clear();
    }
  }
}
