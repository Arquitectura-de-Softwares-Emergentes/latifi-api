package com.latifi.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL 16 container for the whole test JVM. Every Spring context shares it, and Ryuk
 * removes it when the run ends.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  static {
    POSTGRES.start();
  }

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return POSTGRES;
  }
}
