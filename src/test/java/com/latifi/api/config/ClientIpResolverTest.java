package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

  private static ClientIpResolver resolver(boolean trust) {
    return new ClientIpResolver(
        new RateLimitProperties(30, Duration.ofMinutes(1), 5, Duration.ofMinutes(1), trust));
  }

  private static MockHttpServletRequest request(String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");
    if (forwardedFor != null) {
      request.addHeader("X-Forwarded-For", forwardedFor);
    }
    return request;
  }

  @Test
  void trustedHeaderTakesTheFirstEntryAndIgnoresTheRest() {
    assertThat(resolver(true).resolve(request("203.0.113.9, 10.0.0.1, 10.0.0.2")))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void trustedHeaderFallsBackToTheRemoteAddressWhenMissingOrBlank() {
    assertThat(resolver(true).resolve(request(null))).isEqualTo("198.51.100.7");
    assertThat(resolver(true).resolve(request("  "))).isEqualTo("198.51.100.7");
  }

  @Test
  void untrustedHeaderIsIgnored() {
    assertThat(resolver(false).resolve(request("203.0.113.9"))).isEqualTo("198.51.100.7");
  }
}
