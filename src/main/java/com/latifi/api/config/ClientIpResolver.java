package com.latifi.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Picks the address that identifies a client for rate limiting. It reads the first entry of {@code
 * X-Forwarded-For} only when the settings trust the header. Otherwise a client that reaches the API
 * directly could choose its own bucket.
 */
@Component
public class ClientIpResolver {

  static final String FORWARDED_FOR = "X-Forwarded-For";
  private static final int MAX_KEY_LENGTH = 64;

  private final boolean trustForwardedFor;

  public ClientIpResolver(RateLimitProperties properties) {
    this.trustForwardedFor = properties.trustForwardedFor();
  }

  public String resolve(HttpServletRequest request) {
    if (trustForwardedFor) {
      String header = request.getHeader(FORWARDED_FOR);
      if (header != null) {
        String first = header.split(",", 2)[0].trim();
        if (!first.isEmpty() && first.length() <= MAX_KEY_LENGTH) {
          return first;
        }
      }
    }
    return request.getRemoteAddr();
  }
}
