package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.ledger.application.IndexerService;
import com.latifi.api.ledger.domain.CycleResult;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.FakeChainNode;
import com.latifi.api.support.LatifiIntegrationTest;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Only one indexer instance moves the checkpoint at a time. */
@LatifiIntegrationTest
class IndexerLockTest {

  @Autowired private IndexerService indexer;
  @Autowired private FakeChainNode node;
  @Autowired private DatabaseReset databaseReset;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void resetState() {
    node.reset();
    databaseReset.run();
    node.mineTo(20);
    node.emit(15, requested(1));
  }

  private ChainFixtures.Log requested(long loan) {
    return ChainFixtures.loanRequested(
        ChainFixtures.txHash(loan),
        0,
        BigInteger.valueOf(loan),
        ChainFixtures.BORROWER,
        BigInteger.TEN,
        1200,
        90,
        3);
  }

  private int rows() {
    return jdbc.queryForObject("select count(*) from chain_event", Integer.class);
  }

  private int checkpoints() {
    return jdbc.queryForObject("select count(*) from indexer_checkpoint", Integer.class);
  }

  /** Another API instance, as far as the database can tell: a second session holds the lock. */
  @Test
  void aCycleSkipsWhileAnotherConnectionHoldsTheLock() throws SQLException {
    try (Connection other = dataSource.getConnection()) {
      other.setAutoCommit(true);
      lock(other, "select pg_advisory_lock(?)");

      CycleResult skipped = indexer.runOnce();

      assertThat(skipped.skippedReason()).isEqualTo(CycleResult.LOCK_HELD);
      assertThat(skipped.eventsInserted()).isZero();
      assertThat(rows()).isZero();
      assertThat(checkpoints()).isZero();

      lock(other, "select pg_advisory_unlock(?)");
    }

    CycleResult after = indexer.runOnce();

    assertThat(after.skipped()).isFalse();
    assertThat(rows()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select last_block from indexer_checkpoint", Long.class))
        .isEqualTo(20L);
  }

  @Test
  void twoCyclesAtTheSameInstantLeaveNoDuplicateAndNoException() throws Exception {
    node.emit(16, requested(2));
    node.emit(17, requested(3));
    CyclicBarrier start = new CyclicBarrier(2);
    Callable<CycleResult> cycle =
        () -> {
          start.await();
          return indexer.runOnce();
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<CycleResult> first = pool.submit(cycle);
      Future<CycleResult> second = pool.submit(cycle);

      CycleResult a = first.get();
      CycleResult b = second.get();

      assertThat(List.of(a, b)).anyMatch(result -> !result.skipped());
      assertThat(List.of(a, b))
          .allMatch(
              result -> !result.skipped() || CycleResult.LOCK_HELD.equals(result.skippedReason()));
    } finally {
      pool.shutdownNow();
    }

    assertThat(rows()).isEqualTo(3);
    assertThat(
            jdbc.queryForList(
                "select tx_hash from chain_event group by tx_hash, log_index having count(*) > 1"))
        .isEmpty();
    assertThat(jdbc.queryForObject("select last_block from indexer_checkpoint", Long.class))
        .isEqualTo(20L);
  }

  @Test
  void theCheckpointNeverMovesBackward() {
    indexer.runOnce();
    jdbc.update("update indexer_checkpoint set last_block = 500");

    // The node is behind the stored checkpoint, so the cycle stops and leaves it alone.
    CycleResult behind = indexer.runOnce();

    assertThat(behind.skippedReason()).isEqualTo(CycleResult.NODE_BEHIND);
    assertThat(jdbc.queryForObject("select last_block from indexer_checkpoint", Long.class))
        .isEqualTo(500L);
  }

  private static void lock(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, IndexerService.LOCK_KEY);
      statement.execute();
    }
  }
}
