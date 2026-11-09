package com.latifi.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session token settings bound to {@code latifi.jwt}. The secret comes from the environment and has
 * no default, and its text never reaches a log line.
 */
@ConfigurationProperties(prefix = "latifi.jwt")
public record JwtProperties(String secret, Duration ttl, String issuer) {

  @Override
  public String toString() {
    return "JwtProperties[ttl=" + ttl + ", issuer=" + issuer + "]";
  }
}
