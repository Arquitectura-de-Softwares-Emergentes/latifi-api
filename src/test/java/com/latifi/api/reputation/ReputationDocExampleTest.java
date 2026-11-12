package com.latifi.api.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.reputation.domain.LedgerEntry;
import com.latifi.api.reputation.domain.ReputationPolicy;
import com.latifi.api.reputation.domain.ReputationResult;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.ScoreLedger;
import com.latifi.api.reputation.domain.SignalType;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Replays the two worked example tables of docs/reputation-ddd.md through the fold, so the write-up
 * and the code cannot disagree.
 */
class ReputationDocExampleTest {

  private static final Path DOC = Path.of("docs", "reputation-ddd.md");
  private static final int MIN_ROWS = 15;
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  /** | step | signal | days late | delta | score after |, where the middle cells may be empty. */
  private static final Pattern ROW =
      Pattern.compile(
          "^\\|\\s*(\\d+)\\s*\\|\\s*([A-Z_]+)\\s*\\|\\s*(\\d*)\\s*\\|\\s*([+-]?\\d*)\\s*\\|\\s*(\\d+)\\s*\\|\\s*$");

  @Test
  void everyRowOfTheWorkedExamplesMatchesTheFold() throws IOException {
    List<String> lines = Files.readAllLines(DOC, StandardCharsets.UTF_8);

    List<ReputationSignal> segment = new ArrayList<>();
    ReputationPolicy policy = ReputationFixtures.defaultPolicy();
    int replayed = 0;

    for (String line : lines) {
      Matcher row = ROW.matcher(line);
      if (!row.matches()) {
        continue;
      }
      int step = Integer.parseInt(row.group(1));
      String signalName = row.group(2);
      int expectedScore = Integer.parseInt(row.group(5));

      if (signalName.equals("START")) {
        policy = ReputationFixtures.withStart(expectedScore);
        segment = new ArrayList<>();
        continue;
      }

      SignalType type = SignalType.valueOf(signalName);
      int daysLate = row.group(3).isEmpty() ? 0 : Integer.parseInt(row.group(3));
      int expectedDelta = Integer.parseInt(row.group(4).replace("+", ""));
      segment.add(signal(type, step, daysLate));

      ReputationResult result = ScoreLedger.fold(policy, segment);
      LedgerEntry last = result.entries().get(result.entries().size() - 1);
      assertThat(last.delta()).as("delta of the row: %s", line).isEqualTo(expectedDelta);
      assertThat(last.scoreAfter()).as("score of the row: %s", line).isEqualTo(expectedScore);
      replayed++;
    }

    assertThat(replayed).as("rows replayed from %s", DOC).isGreaterThanOrEqualTo(MIN_ROWS);
  }

  private static ReputationSignal signal(SignalType type, int step, int daysLate) {
    Instant occurredAt = BASE.plusSeconds(step);
    String sourceKey = "row-" + step + "-" + type;
    if (type == SignalType.PROFILE_COMPLETED) {
      return new ReputationSignal(type, occurredAt, 0, 0, null, null, 0, null, "profile");
    }
    return new ReputationSignal(
        type,
        occurredAt,
        step,
        0,
        "loan-" + step,
        BigInteger.valueOf(10_000_000L),
        daysLate,
        "0x" + step,
        sourceKey);
  }
}
