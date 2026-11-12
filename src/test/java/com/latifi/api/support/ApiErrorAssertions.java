package com.latifi.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Checks that an error response has the exact ApiError shape and nothing else. */
public final class ApiErrorAssertions {

  private static final ObjectMapper JSON = new ObjectMapper();

  private ApiErrorAssertions() {}

  public static void assertApiError(int status, String body, String expectedCode) {
    JsonNode node = parse(body);
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    assertThat(names)
        .as("fields of %s", body)
        .containsExactlyInAnyOrder("status", "code", "message");
    assertThat(node.get("status").asInt()).isEqualTo(status);
    assertThat(node.get("code").asText()).isEqualTo(expectedCode);
    assertThat(node.get("message").isTextual()).isTrue();
    assertThat(node.get("message").asText()).isNotBlank();
  }

  public static void assertMessage(String body, String text) {
    assertThat(parse(body).get("message").asText()).isEqualTo(text);
  }

  private static JsonNode parse(String body) {
    try {
      return JSON.readTree(body);
    } catch (IOException | IllegalArgumentException e) {
      throw new AssertionError("The response body is not JSON: " + body, e);
    }
  }
}
