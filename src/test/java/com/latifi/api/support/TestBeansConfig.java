package com.latifi.api.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

  @Bean
  DynamicPropertyRegistrar jwtSecretRegistrar() {
    return registry -> registry.add("latifi.jwt.secret", () -> JWT_SECRET);
  }
}
