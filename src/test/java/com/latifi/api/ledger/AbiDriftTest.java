package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Event;

/**
 * Compares the Java event definitions with the frozen v0 ABI of the contracts repository. The file
 * {@code abi/loan-events.v0.json} in the test resources is a byte copy of {@code
 * latifi-contracts/abi/loan-events.v0.json}. A change of the contract ABI means a new version file
 * and a conscious update of this test and of {@link LoanBookEvents}.
 */
class AbiDriftTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private record Expected(Event event, String topic0) {}

  private static final Map<String, Expected> JAVA =
      Map.of(
          "LoanRequested",
          new Expected(LoanBookEvents.LOAN_REQUESTED, LoanBookEvents.TOPIC_LOAN_REQUESTED),
          "LoanFunded",
          new Expected(LoanBookEvents.LOAN_FUNDED, LoanBookEvents.TOPIC_LOAN_FUNDED),
          "LoanRepaid",
          new Expected(LoanBookEvents.LOAN_REPAID, LoanBookEvents.TOPIC_LOAN_REPAID),
          "LoanDefaulted",
          new Expected(LoanBookEvents.LOAN_DEFAULTED, LoanBookEvents.TOPIC_LOAN_DEFAULTED));

  private static Map<String, JsonNode> abiEvents() throws Exception {
    Map<String, JsonNode> events = new LinkedHashMap<>();
    try (InputStream in = AbiDriftTest.class.getResourceAsStream("/abi/loan-events.v0.json")) {
      assertThat(in).as("the copied ABI file").isNotNull();
      for (JsonNode entry : JSON.readTree(in)) {
        if ("event".equals(entry.path("type").asText())) {
          events.put(entry.path("name").asText(), entry);
        }
      }
    }
    return events;
  }

  @Test
  void abiHoldsExactlyTheFourEventsTheIndexerKnows() throws Exception {
    assertThat(abiEvents().keySet()).containsExactlyInAnyOrderElementsOf(JAVA.keySet());
  }

  @Test
  void everyTopic0ConstantEqualsTheHashOfTheAbiSignature() throws Exception {
    for (Map.Entry<String, JsonNode> abi : abiEvents().entrySet()) {
      List<String> types = new ArrayList<>();
      abi.getValue().path("inputs").forEach(input -> types.add(input.path("type").asText()));
      String signature = abi.getKey() + "(" + String.join(",", types) + ")";
      assertThat(EventEncoder.buildEventSignature(signature))
          .as("topic0 of %s", signature)
          .isEqualTo(JAVA.get(abi.getKey()).topic0());
    }
  }

  @Test
  void indexedFlagsOfEveryInputMatchTheJavaDefinition() throws Exception {
    for (Map.Entry<String, JsonNode> abi : abiEvents().entrySet()) {
      List<Boolean> abiFlags = new ArrayList<>();
      abi.getValue()
          .path("inputs")
          .forEach(input -> abiFlags.add(input.path("indexed").asBoolean()));
      List<Boolean> javaFlags =
          JAVA.get(abi.getKey()).event().getParameters().stream()
              .map(TypeReference::isIndexed)
              .toList();
      assertThat(javaFlags).as("indexed flags of %s", abi.getKey()).isEqualTo(abiFlags);
    }
  }

  @Test
  void javaDefinitionsListTheSameFieldCountAsTheAbi() throws Exception {
    for (Map.Entry<String, JsonNode> abi : abiEvents().entrySet()) {
      assertThat(JAVA.get(abi.getKey()).event().getParameters())
          .as("fields of %s", abi.getKey())
          .hasSize(abi.getValue().path("inputs").size());
    }
  }
}
