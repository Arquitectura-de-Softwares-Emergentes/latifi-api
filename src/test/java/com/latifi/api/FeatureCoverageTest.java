package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every operation of the contract has a Gherkin scenario tagged {@code @op-<operationId>}, and no
 * scenario names an operation that the contract lacks.
 */
class FeatureCoverageTest {

  private static final Path FEATURES = Path.of("src/test/resources/features");
  private static final Pattern OP_TAG = Pattern.compile("@op-([A-Za-z0-9]+)");

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

  @Test
  void everyOperationHasATaggedScenarioAndNoTagIsUnknown() throws IOException {
    Set<String> tagged = new TreeSet<>();
    try (Stream<Path> files = Files.list(FEATURES)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".feature")).toList()) {
        Matcher matcher = OP_TAG.matcher(Files.readString(file));
        while (matcher.find()) {
          tagged.add(matcher.group(1));
        }
      }
    }
    Set<String> missing = new TreeSet<>(OPERATION_IDS);
    missing.removeAll(tagged);
    Set<String> unknown = new TreeSet<>(tagged);
    unknown.removeAll(OPERATION_IDS);

    assertThat(missing).as("operations without an @op- scenario").isEmpty();
    assertThat(unknown).as("@op- tags that name no operation of the contract").isEmpty();
  }
}
