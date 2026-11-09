package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.support.LatifiIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** The documentation is public: no token is needed for the page or for the generated file. */
@LatifiIntegrationTest
class SwaggerUiTest {

  @Value("${local.server.port}")
  private int port;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

  @Test
  void swaggerPageAnswersWithoutAToken() throws Exception {
    HttpResponse<String> response = get("/swagger-ui.html");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body().toLowerCase()).contains("swagger");
  }

  @Test
  void apiDocsAnswerWithoutAToken() throws Exception {
    assertThat(get("/v3/api-docs").statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
