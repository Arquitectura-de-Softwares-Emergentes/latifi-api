package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.support.LatifiIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/** The switch behind {@code LATIFI_SWAGGER_ENABLED} turns the documentation off. */
@LatifiIntegrationTest
@TestPropertySource(
    properties = {
      "springdoc.swagger-ui.enabled=false",
      "springdoc.api-docs.enabled=false",
      "latifi.test.context=swagger-disabled"
    })
class SwaggerDisabledTest {

  @Value("${local.server.port}")
  private int port;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

  @Test
  void apiDocsDoNotAnswer() throws Exception {
    assertThat(get("/v3/api-docs").statusCode()).isNotEqualTo(200);
  }

  @Test
  void swaggerPageDoesNotAnswer() throws Exception {
    assertThat(get("/swagger-ui.html").statusCode()).isNotEqualTo(200);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
