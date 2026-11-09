package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.latifi.api.ledger.infrastructure.IndexerScheduler;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.FakeChainNode;
import com.latifi.api.support.LatifiIntegrationTest;
import java.math.BigInteger;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

/**
 * A real {@code @Scheduled} cycle every 100 ms against the simulated node of this context. The
 * context closes after the class, so the running scheduler never touches the tables of other tests.
 */
@LatifiIntegrationTest
@TestPropertySource(
    properties = {"latifi.indexer.enabled=true", "latifi.indexer.poll-interval=PT0.1S"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexerSchedulerTest {

  private static final Logger LOG = LoggerFactory.getLogger(IndexerSchedulerTest.class);
  private static final Duration CEILING = Duration.ofSeconds(30);

  @Autowired private FakeChainNode node;
  @Autowired private DatabaseReset databaseReset;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IndexerScheduler scheduler;
  @Autowired private ScheduledAnnotationBeanPostProcessor schedules;

  @BeforeEach
  void resetState() {
    // The node goes first. A cycle that runs between the two resets then finds a node that is
    // behind the stored checkpoint, so it writes nothing.
    node.reset();
    databaseReset.run();
  }

  @AfterAll
  void stopTheSchedule() {
    schedules.postProcessBeforeDestruction(scheduler, "indexerScheduler");
  }

  private int rows() {
    return jdbc.queryForObject("select count(*) from chain_event", Integer.class);
  }

  private ChainFixtures.Log requested(long seed) {
    return ChainFixtures.loanRequested(
        ChainFixtures.txHash(seed),
        0,
        BigInteger.valueOf(seed),
        ChainFixtures.BORROWER,
        BigInteger.TEN,
        1200,
        90,
        3);
  }

  @Test
  void theScheduledCycleMirrorsAnEmittedEventWithinSeconds() {
    node.mineTo(5);
    long started = System.nanoTime();
    node.emit(6, requested(1));

    await().atMost(CEILING).pollInterval(Duration.ofMillis(50)).until(() -> rows() == 1);

    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    LOG.info("The scheduled indexer mirrored the event in {} ms", elapsed.toMillis());
    assertThat(elapsed).isLessThan(CEILING);
    assertThat(jdbc.queryForObject("select last_block from indexer_checkpoint", Long.class))
        .isEqualTo(6L);
  }

  @Test
  void theScheduleSurvivesANodeOutageAndCatchesUpAfterIt() {
    node.mineTo(3);
    node.down(true);
    node.emit(4, requested(2));

    await()
        .atMost(CEILING)
        .until(() -> node.requests().stream().filter(r -> r.method().equals("down")).count() >= 3);
    assertThat(rows()).isZero();

    node.down(false);
    await().atMost(CEILING).pollInterval(Duration.ofMillis(50)).until(() -> rows() == 1);
  }
}
