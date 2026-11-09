package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.ledger.infrastructure.IndexerScheduler;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.ContractAssertions;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.FakeChainNode;
import com.latifi.api.support.LatifiIntegrationTest;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

/**
 * The headline claim of the phase: a log that the contract emits shows up through the API within
 * seconds, with the real {@code @Scheduled} indexer and no manual cycle. The context closes after
 * the class, so the running scheduler never touches the tables of other tests.
 */
@LatifiIntegrationTest
@TestPropertySource(
    properties = {"latifi.indexer.enabled=true", "latifi.indexer.poll-interval=PT0.1S"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoanVisibilityTest {

  private static final Logger LOG = LoggerFactory.getLogger(LoanVisibilityTest.class);
  private static final Duration CEILING = Duration.ofSeconds(30);
  private static final long FUNDED_AT = 1_800_000_000L;
  private static final long DAY = 86_400L;

  @Autowired private FakeChainNode node;
  @Autowired private DatabaseReset databaseReset;
  @Autowired private IndexerScheduler scheduler;
  @Autowired private ScheduledAnnotationBeanPostProcessor schedules;

  @Value("${local.server.port}")
  private int port;

  private ApiClient api;

  @BeforeEach
  void resetState() {
    // The node goes first, so a cycle that runs between the two resets writes nothing.
    node.reset();
    databaseReset.run();
    api = new ApiClient(port);
  }

  @AfterAll
  void stopTheSchedule() {
    schedules.postProcessBeforeDestruction(scheduler, "indexerScheduler");
  }

  @Test
  void aChainEventReachesTheLoanRouteWithinSeconds() {
    api.withBearer(TestWallet.create().login(api));
    node.mineTo(5);

    long requestedAt = System.nanoTime();
    node.emit(
        6,
        ChainFixtures.loanRequested(
            ChainFixtures.txHash(1),
            0,
            BigInteger.valueOf(7),
            ChainFixtures.BORROWER,
            BigInteger.valueOf(1_000_000_000L),
            1000,
            90,
            3));
    JsonNode open = awaitLoan("/api/v1/loans/7", "OPEN");
    Duration openWait = Duration.ofNanos(System.nanoTime() - requestedAt);
    LOG.info("LoanRequested reached GET /loans/7 in {} ms", openWait.toMillis());
    assertThat(openWait).isLessThan(CEILING);

    assertThat(open.get("loanId").asText()).isEqualTo("7");
    assertThat(open.get("borrower").asText()).isEqualTo(ChainFixtures.BORROWER);
    assertThat(open.has("lender")).isFalse();
    assertThat(open.get("principal").asText()).isEqualTo("1000000000");
    assertThat(open.get("totalDue").asText()).isEqualTo("1024657534");
    assertThat(open.get("amountRepaid").asText()).isEqualTo("0");
    assertThat(open.get("installmentsPaid").asInt()).isZero();
    assertThat(open.get("borrowerReputation").get("score").asInt()).isEqualTo(500);
    assertThat(open.get("borrowerReputation").get("level").asText()).isEqualTo("NEW");

    long fundedAfter = System.nanoTime();
    node.emit(
        8,
        ChainFixtures.loanFunded(
            ChainFixtures.txHash(2),
            0,
            BigInteger.valueOf(7),
            ChainFixtures.LENDER,
            ChainFixtures.BORROWER,
            BigInteger.valueOf(1_000_000_000L),
            FUNDED_AT,
            FUNDED_AT + 90 * DAY));
    JsonNode active = awaitLoan("/api/v1/loans/7", "ACTIVE");
    Duration fundedWait = Duration.ofNanos(System.nanoTime() - fundedAfter);
    LOG.info("LoanFunded reached GET /loans/7 in {} ms", fundedWait.toMillis());
    assertThat(fundedWait).isLessThan(CEILING);

    assertThat(active.get("lender").asText()).isEqualTo(ChainFixtures.LENDER);
    assertThat(Instant.parse(active.get("fundedAt").asText()))
        .isEqualTo(Instant.ofEpochSecond(FUNDED_AT));
    assertThat(Instant.parse(active.get("finalDueDate").asText()))
        .isEqualTo(Instant.ofEpochSecond(FUNDED_AT + 90 * DAY));
    assertThat(Instant.parse(active.get("nextDueDate").asText()))
        .isEqualTo(Instant.ofEpochSecond(FUNDED_AT + 30 * DAY));
    assertThat(active.get("totalDue").asText()).isEqualTo("1024657534");
  }

  /** Polls the route until it answers 200 with the status, checking the contract every time. */
  private JsonNode awaitLoan(String path, String status) {
    await()
        .atMost(CEILING)
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              api.get(path);
              return api.status() == 200 && status.equals(api.json().get("status").asText());
            });
    ContractAssertions.assertResponseConforms(
        Request.Method.GET, path, api.status(), api.header("content-type"), api.body());
    return api.json();
  }
}
