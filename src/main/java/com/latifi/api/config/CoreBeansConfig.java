package com.latifi.api.config;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared infrastructure beans. Tests replace the clock with a controllable one. */
@Configuration(proxyBeanMethods = false)
public class CoreBeansConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SecureRandom secureRandom() {
    return new SecureRandom();
  }
}
