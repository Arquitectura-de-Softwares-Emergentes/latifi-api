package com.latifi.api.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Beans that replace production ones in tests. Later plans add their own here. */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeansConfig {

  public static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock(START);
  }
}
