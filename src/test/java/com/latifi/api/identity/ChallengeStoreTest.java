package com.latifi.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.identity.domain.Challenge;
import com.latifi.api.identity.domain.ChallengeStore;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.LatifiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@LatifiIntegrationTest
class ChallengeStoreTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final String ADDRESS = "0x" + "ab".repeat(20);

  @Autowired private ChallengeStore store;
  @Autowired private DatabaseReset reset;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void cleanTables() {
    reset.run();
  }

  private Challenge save(Instant issuedAt) {
    Challenge challenge =
        new Challenge(
            UUID.randomUUID(),
            ADDRESS,
            "nonce",
            "message",
            issuedAt,
            issuedAt.plus(Duration.ofMinutes(5)),
            null);
    store.save(challenge);
    return challenge;
  }

  @Test
  void twoThreadsConsumingOneChallengeProduceExactlyOneWinner() throws Exception {
    Challenge challenge = save(NOW);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Callable<Boolean> task =
          () -> {
            start.await();
            return store.consume(challenge.id(), NOW);
          };
      results.add(pool.submit(task));
    }
    start.countDown();
    int winners = 0;
    for (Future<Boolean> result : results) {
      if (result.get()) {
        winners++;
      }
    }
    pool.shutdown();

    assertThat(winners).isEqualTo(1);
  }

  @Test
  void consumeRefusesAnExpiredChallenge() {
    Challenge challenge = save(NOW);

    assertThat(store.consume(challenge.id(), NOW.plus(Duration.ofMinutes(5)))).isFalse();
    assertThat(store.find(challenge.id()).orElseThrow().usedAt()).isNull();
  }

  @Test
  void deleteExpiredBeforeRemovesOnlyRowsOlderThanTheCutoff() {
    Challenge old = save(NOW.minus(Duration.ofHours(30)));
    Challenge recent = save(NOW.minus(Duration.ofHours(1)));

    int removed = store.deleteExpiredBefore(NOW.minus(Duration.ofHours(24)));

    assertThat(removed).isEqualTo(1);
    assertThat(store.find(old.id())).isEmpty();
    assertThat(store.find(recent.id())).isPresent();
    assertThat(jdbc.queryForObject("select count(*) from auth_challenge", Integer.class))
        .isEqualTo(1);
  }
}
