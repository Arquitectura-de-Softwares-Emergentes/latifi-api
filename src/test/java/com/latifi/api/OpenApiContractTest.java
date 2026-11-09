package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the invariants of contract 0.2.0. The test reads the YAML file only, so it needs no Spring
 * context and no database.
 */
class OpenApiContractTest {

  private static final String CONTRACT = "openapi/latifi-api.v0.yaml";

  private static final Set<String> OPERATION_IDS =
      Set.of(
          "issueChallenge",
          "verifyChallenge",
          "getProfile",
          "upsertProfile",
          "getReputation",
          "getReputationHistory",
          "listLoans",
          "getLoan",
          "convertAmount",
          "listRates");

  private static final List<String> ERROR_CODES =
      List.of(
          "INVALID_ADDRESS",
          "VALIDATION_FAILED",
          "MALFORMED_REQUEST",
          "UNSUPPORTED_CURRENCY",
          "INVALID_CREDENTIALS",
          "UNAUTHORIZED",
          "FORBIDDEN_NOT_OWNER",
          "NOT_FOUND",
          "PROFILE_NOT_FOUND",
          "REPUTATION_NOT_FOUND",
          "LOAN_NOT_FOUND",
          "METHOD_NOT_ALLOWED",
          "UNSUPPORTED_MEDIA_TYPE",
          "TOO_MANY_REQUESTS",
          "INTERNAL_ERROR",
          "RATE_UNAVAILABLE");

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

  private static Map<String, Object> doc;

  @BeforeAll
  static void loadContract() throws IOException {
    try (InputStream in = Files.newInputStream(Path.of(CONTRACT))) {
      doc = new Yaml().load(in);
    }
  }

  @Test
  void versionIsZeroTwoZero() {
    assertThat(map(doc, "info")).containsEntry("version", "0.2.0");
  }

  @Test
  void operationIdsAreTheTenOfThePhase() {
    assertThat(operations().values().stream().map(op -> (String) op.get("operationId")).toList())
        .hasSize(10)
        .containsExactlyInAnyOrderElementsOf(OPERATION_IDS);
  }

  @Test
  void scoresRunFromZeroToOneThousand() {
    for (String[] path :
        new String[][] {
          {"ReputationResponse", "score"},
          {"ReputationEvent", "scoreAfter"},
          {"BorrowerReputation", "score"}
        }) {
      Map<String, Object> property = map(map(schema(path[0]), "properties"), path[1]);
      assertThat(property.get("maximum")).as(path[0] + "." + path[1]).isEqualTo(1000);
      assertThat(property.get("minimum")).as(path[0] + "." + path[1]).isEqualTo(0);
    }
  }

  @Test
  void reputationEventDoesNotRequireTransactionReference() {
    Map<String, Object> event = schema("ReputationEvent");
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) event.get("required");
    assertThat(required)
        .containsExactlyInAnyOrder("id", "type", "delta", "scoreAfter", "occurredAt");
    Map<String, Object> properties = map(event, "properties");
    assertThat(map(properties, "txHash")).containsEntry("nullable", true);
    assertThat(map(properties, "logIndex")).containsEntry("nullable", true);
  }

  @Test
  void getProfileAcceptsAnonymousAndBearerCallers() {
    Map<String, Object> op = operations().get("getProfile");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> security = (List<Map<String, Object>>) op.get("security");
    assertThat(security).hasSize(2);
    assertThat(security.get(0)).isEmpty();
    assertThat(security.get(1)).containsOnlyKeys("bearerAuth");
  }

  @Test
  void exchangeRateOperationsDocumentServiceUnavailable() {
    for (String id : List.of("convertAmount", "listRates")) {
      assertThat(map(operations().get(id), "responses")).as(id).containsKey("503");
    }
  }

  @Test
  void acceptLanguageHasNoEnum() {
    Map<String, Object> parameter = map(map(components(), "parameters"), "AcceptLanguage");
    Map<String, Object> schema = map(parameter, "schema");
    assertThat(schema).doesNotContainKey("enum").containsEntry("type", "string");
  }

  @Test
  void apiErrorListsTheSixteenStableCodes() {
    @SuppressWarnings("unchecked")
    List<String> codes = (List<String>) schema("ApiError").get("x-error-codes");
    assertThat(codes).hasSize(16);
    assertThat(new HashSet<>(codes)).hasSameSizeAs(codes).isEqualTo(new HashSet<>(ERROR_CODES));
  }

  @Test
  void validatorBuildsFromTheFile() {
    assertThatCode(() -> OpenApiInteractionValidator.createFor(CONTRACT).build())
        .doesNotThrowAnyException();
  }

  private static Map<String, Object> components() {
    return map(doc, "components");
  }

  private static Map<String, Object> schema(String name) {
    return map(map(components(), "schemas"), name);
  }

  private static Map<String, Map<String, Object>> operations() {
    Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
    Map<String, Object> paths = map(doc, "paths");
    for (Map.Entry<String, Object> path : paths.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> item = (Map<String, Object>) path.getValue();
      for (Map.Entry<String, Object> entry : item.entrySet()) {
        if (HTTP_METHODS.contains(entry.getKey())) {
          @SuppressWarnings("unchecked")
          Map<String, Object> op = (Map<String, Object>) entry.getValue();
          result.put((String) op.get("operationId"), op);
        }
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    assertThat(value).as("key " + key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }
}
