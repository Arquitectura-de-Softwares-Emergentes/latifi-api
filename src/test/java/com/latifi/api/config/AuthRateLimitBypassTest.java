package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.identity.infrastructure.AuthRateLimiter;
import com.latifi.api.support.LatifiIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * The per client limit of the auth endpoints must hold for every spelling of the path that the
 * router still sends to the auth controller, percent-encoded letters included.
 */
@LatifiIntegrationTest
@TestPropertySource(properties = "latifi.test.context=auth-rate-limit-bypass")
class AuthRateLimitBypassTest {

  /** The capacity that the test beans configure for the per client limit. */
  private static final int CAPACITY = 10;

  @Autowired private AuthRateLimiter limiter;

  @Value("${local.server.port}")
  private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void freshBuckets() {
    limiter.reset();
  }

  @Test
  void aPercentEncodedSegmentIsLimitedLikeThePlainPath() throws Exception {
    // The first request proves the encoded path still reaches the auth routes: the empty body is
    // refused as a bad request and not as 404 or 401.
    assertThat(post("/api/v1/%61uth/challenge").statusCode()).isEqualTo(400);
    for (int i = 1; i < CAPACITY; i++) {
      assertThat(post("/api/v1/%61uth/challenge").statusCode()).isNotEqualTo(429);
    }

    assertThat(post("/api/v1/%61uth/challenge").statusCode()).isEqualTo(429);
  }

  @Test
  void anEncodedLastSegmentIsLimitedToo() throws Exception {
    for (int i = 0; i < CAPACITY; i++) {
      post("/api/v1/auth/%76erify");
    }

    assertThat(post("/api/v1/auth/%76erify").statusCode()).isEqualTo(429);
  }

  private HttpResponse<String> post(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
