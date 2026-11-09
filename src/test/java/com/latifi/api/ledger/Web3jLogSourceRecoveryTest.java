package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latifi.api.ledger.domain.ChainRpcErrorException;
import com.latifi.api.ledger.infrastructure.Web3jLogSource;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.FakeChainNode;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/** What the log source learns from errors, and how it recovers what it learned. */
class Web3jLogSourceRecoveryTest {

  private static final int RECOVERY_AFTER = 3;

  private FakeChainNode node;
  private Web3j web3j;
  private Web3jLogSource source;

  @BeforeEach
  void startNode() {
    node = new FakeChainNode();
    web3j = Web3j.build(new HttpService(node.url()));
    source = new Web3jLogSource(web3j, ChainFixtures.LOAN_BOOK, RECOVERY_AFTER);
    node.mineTo(100);
    node.emit(
        13,
        ChainFixtures.loanRequested(
            ChainFixtures.txHash(1),
            0,
            BigInteger.ONE,
            ChainFixtures.BORROWER,
            BigInteger.TEN,
            1200,
            90,
            3));
  }

  @AfterEach
  void stopNode() {
    web3j.shutdown();
    node.stop();
  }

  @Test
  void aRateLimitErrorIsNotHalvedAndTeachesNothing() {
    node.failLogs(1, -32005, "daily request count exceeded, request rate limited");

    assertThatThrownBy(() -> source.logs(1, 95)).isInstanceOf(ChainRpcErrorException.class);

    assertThat(node.requests("eth_getLogs")).hasSize(1);
    assertThat(source.suggestedBatchSize(500)).isEqualTo(500);
    assertThat(source.logs(1, 95)).hasSize(1);
    assertThat(source.suggestedBatchSize(500)).isEqualTo(500);
  }

  @Test
  void aLaggingNodeErrorIsNotHalvedAndTeachesNothing() {
    node.failLogs(1, -32602, "block range extends beyond current head block");

    assertThatThrownBy(() -> source.logs(1, 95)).isInstanceOf(ChainRpcErrorException.class);

    assertThat(node.requests("eth_getLogs")).hasSize(1);
    assertThat(source.suggestedBatchSize(500)).isEqualTo(500);
  }

  @Test
  void aSizeThatTheNodeKeepsAcceptingGrowsBack() {
    node.maxLogRange(10);
    source.logs(1, 95);
    assertThat(source.suggestedBatchSize(500)).isEqualTo(6);

    node.maxLogRange(Integer.MAX_VALUE);
    for (int call = 0; call < RECOVERY_AFTER - 1; call++) {
      source.logs(1, 6);
      assertThat(source.suggestedBatchSize(500)).isEqualTo(6);
    }
    source.logs(1, 6);

    assertThat(source.suggestedBatchSize(500)).isEqualTo(12);
  }

  @Test
  void callsThatNeverReachTheLearnedSizeDoNotCountTowardsRecovery() {
    node.maxLogRange(10);
    source.logs(1, 95);
    node.maxLogRange(Integer.MAX_VALUE);

    for (int call = 0; call < RECOVERY_AFTER * 2; call++) {
      source.logs(1, 3);
    }

    assertThat(source.suggestedBatchSize(500)).isEqualTo(6);
  }

  @Test
  void aRejectionAfterGrowingLearnsTheLimitAgain() {
    node.maxLogRange(10);
    source.logs(1, 95);
    for (int call = 0; call < RECOVERY_AFTER; call++) {
      source.logs(1, 6);
    }
    assertThat(source.suggestedBatchSize(500)).isEqualTo(12);

    // The cap is still 10, so a range of 12 is refused and the size falls back to a size it
    // accepts.
    source.logs(1, 12);

    assertThat(source.suggestedBatchSize(500)).isEqualTo(6);
  }
}
