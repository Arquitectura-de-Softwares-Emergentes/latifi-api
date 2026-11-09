package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.latifi.api.identity.infrastructure.ChallengeCleanupJob;
import com.latifi.api.ledger.domain.BlockInfo;
import com.latifi.api.ledger.domain.ChainLogSource;
import com.latifi.api.ledger.domain.RawLog;
import com.latifi.api.support.LatifiIntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * An indexer cycle that never returns must not stop the other scheduled jobs. The node of this
 * context hangs on the first call, and the cleanup job, which runs every 100 ms here, has to keep
 * running meanwhile.
 */
@LatifiIntegrationTest
@TestPropertySource(
    properties = {
      "latifi.indexer.enabled=true",
      "latifi.indexer.poll-interval=PT0.1S",
      "latifi.auth.cleanup-interval=PT0.1S",
      "latifi.test.context=scheduler-isolation"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerIsolationTest {

  private static final CountDownLatch STUCK = new CountDownLatch(1);
  private static final CountDownLatch CYCLE_STARTED = new CountDownLatch(1);
  private static final AtomicReference<String> CYCLE_THREAD = new AtomicReference<>();

  /** A node whose every call hangs until the test ends. */
  @TestConfiguration(proxyBeanMethods = false)
  static class HangingNode {

    @Bean
    @Primary
    ChainLogSource hangingLogSource() {
      return new ChainLogSource() {
        @Override
        public long latestBlock() {
          return hang();
        }

        @Override
        public long chainId() {
          return hang();
        }

        @Override
        public List<RawLog> logs(long fromBlock, long toBlock) {
          hang();
          return List.of();
        }

        @Override
        public BlockInfo block(long number) {
          hang();
          return null;
        }

        @Override
        public int suggestedBatchSize(int configured) {
          return configured;
        }

        private long hang() {
          CYCLE_THREAD.compareAndSet(null, Thread.currentThread().getName());
          CYCLE_STARTED.countDown();
          try {
            STUCK.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return 0;
        }
      };
    }
  }

  @MockitoSpyBean private ChallengeCleanupJob cleanup;

  @AfterAll
  void release() {
    STUCK.countDown();
  }

  @Test
  void aStuckIndexerCycleLeavesTheCleanupJobRunning() throws Exception {
    assertThat(CYCLE_STARTED.await(10, TimeUnit.SECONDS)).isTrue();

    verify(cleanup, timeout(10_000).atLeast(3)).run();
    assertThat(CYCLE_THREAD.get()).startsWith("indexer-");
  }
}
