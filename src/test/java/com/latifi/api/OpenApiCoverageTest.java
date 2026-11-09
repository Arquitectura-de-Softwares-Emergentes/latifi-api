package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.support.LatifiIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.yaml.snakeyaml.Yaml;

/**
 * The generated documentation must say what the contract file says: the same operations (method,
 * path, operation id) and the same security shape. A controller that drifts from the contract, or a
 * contract edit that no controller follows, fails this test.
 */
@LatifiIntegrationTest
class OpenApiCoverageTest {

  private static final String CONTRACT = "openapi/latifi-api.v0.yaml";
  private static final String BASE = "/api/v1";
  private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Value("${local.server.port}")
  private int port;

  @Test
  void generatedDocumentationMatchesTheContract() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("json");

    Map<String, String> generated = generatedOperations(JSON.readTree(response.body()));
    Map<String, String> expected = contractOperations();

    Set<String> missing = new TreeSet<>(expected.keySet());
    missing.removeAll(generated.keySet());
    Set<String> extra = new TreeSet<>(generated.keySet());
    extra.removeAll(expected.keySet());
    System.out.println("OpenAPI drift, missing from the code: " + missing);
    System.out.println("OpenAPI drift, extra in the code: " + extra);

    assertThat(missing).as("operations the contract defines and the code lacks").isEmpty();
    assertThat(extra).as("operations the code serves and the contract lacks").isEmpty();
    assertThat(generated).hasSize(10);
    assertThat(generated).as("security shape of every operation").isEqualTo(expected);
  }

  /** Key: "METHOD path operationId". Value: the security shape. */
  private static Map<String, String> generatedOperations(JsonNode doc) {
    Map<String, String> result = new TreeMap<>();
    JsonNode globalSecurity = doc.path("security");
    Iterator<Map.Entry<String, JsonNode>> paths = doc.path("paths").fields();
    while (paths.hasNext()) {
      Map.Entry<String, JsonNode> path = paths.next();
      Iterator<Map.Entry<String, JsonNode>> methods = path.getValue().fields();
      while (methods.hasNext()) {
        Map.Entry<String, JsonNode> method = methods.next();
        if (!HTTP_METHODS.contains(method.getKey())) {
          continue;
        }
        JsonNode operation = method.getValue();
        JsonNode security = operation.has("security") ? operation.get("security") : globalSecurity;
        List<Set<String>> alternatives = new java.util.ArrayList<>();
        for (JsonNode requirement : security) {
          Set<String> schemes = new TreeSet<>();
          requirement.fieldNames().forEachRemaining(schemes::add);
          alternatives.add(schemes);
        }
        result.put(
            key(method.getKey(), path.getKey(), operation.path("operationId").asText()),
            shape(alternatives));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> contractOperations() throws IOException {
    Map<String, Object> doc;
    try (InputStream in = Files.newInputStream(Path.of(CONTRACT))) {
      doc = new Yaml().load(in);
    }
    List<Map<String, List<String>>> globalSecurity =
        (List<Map<String, List<String>>>) doc.get("security");
    Map<String, String> result = new TreeMap<>();
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
    for (Map.Entry<String, Map<String, Object>> path : paths.entrySet()) {
      for (Map.Entry<String, Object> method : path.getValue().entrySet()) {
        if (!HTTP_METHODS.contains(method.getKey())) {
          continue;
        }
        Map<String, Object> operation = (Map<String, Object>) method.getValue();
        List<Map<String, List<String>>> security =
            operation.containsKey("security")
                ? (List<Map<String, List<String>>>) operation.get("security")
                : globalSecurity;
        List<Set<String>> alternatives = new java.util.ArrayList<>();
        for (Map<String, List<String>> requirement : security) {
          alternatives.add(new TreeSet<>(requirement.keySet()));
        }
        result.put(
            key(method.getKey(), BASE + path.getKey(), (String) operation.get("operationId")),
            shape(alternatives));
      }
    }
    return result;
  }

  private static String key(String method, String path, String operationId) {
    return method.toUpperCase(Locale.ROOT) + " " + path + " " + operationId;
  }

  /** anonymous: no requirement. optional bearer: an empty alternative next to bearerAuth. */
  private static String shape(List<Set<String>> alternatives) {
    if (alternatives.isEmpty()) {
      return "anonymous";
    }
    boolean hasEmpty = alternatives.stream().anyMatch(Set::isEmpty);
    boolean hasBearer = alternatives.stream().anyMatch(s -> s.contains("bearerAuth"));
    if (hasEmpty && hasBearer) {
      return "optional bearer";
    }
    if (hasBearer && alternatives.size() == 1) {
      return "bearer required";
    }
    return "other " + alternatives;
  }
}
