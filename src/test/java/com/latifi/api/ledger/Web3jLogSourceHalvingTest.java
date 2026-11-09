package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latifi.api.ledger.domain.ChainRpcException;
import com.latifi.api.ledger.domain.RawLog;
import com.latifi.api.ledger.infrastructure.Web3jLogSource;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.FakeChainNode;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/** The log source against a node that caps the block range of {@code eth_getLogs}. */
class Web3jLogSourceHalvingTest {

  private FakeChainNode node;
  private Web3j web3j;
  private Web3jLogSource source;

  @BeforeEach
  void startNode() {
    node = new FakeChainNode();
    web3j = Web3j.build(new HttpService(node.url()));
    source = new Web3jLogSource(web3j, ChainFixtures.LOAN_BOOK);
    node.mineTo(100);
    // One log every ten blocks, so every piece of a split range can hold some.
    for (int loan = 1; loan <= 9; loan++) {
      node.emit(loan * 10L + 3, requested(loan));
    }
  }

  @AfterEach
  void stopNode() {
    web3j.shutdown();
    node.stop();
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

  private static List<Long> blocks(List<RawLog> logs) {
    return logs.stream().map(RawLog::blockNumber).toList();
  }

  @Test
  void aRangeWiderThanTheLimitIsSplitAndEveryLogComesBackInOrder() {
    node.maxLogRange(10);

    List<RawLog> logs = source.logs(1, 95);

    assertThat(blocks(logs)).containsExactly(13L, 23L, 33L, 43L, 53L, 63L, 73L, 83L, 93L);
    // Ranges of 95, 48, 24 and 12 blocks are rejected, the first range of 6 blocks is accepted.
    assertThat(node.rejectedRangeCount()).isEqualTo(4);
  }

  @Test
  void theAcceptedSizeIsRememberedSoTheSecondCallCausesNoRejection() {
    node.maxLogRange(10);

    source.logs(1, 95);
    int afterFirstCall = node.rejectedRangeCount();
    List<RawLog> again = source.logs(1, 95);

    assertThat(afterFirstCall).isEqualTo(4);
    assertThat(node.rejectedRangeCount()).isEqualTo(afterFirstCall);
    assertThat(again).hasSize(9);
    assertThat(source.suggestedBatchSize(500)).isEqualTo(6);
    assertThat(source.suggestedBatchSize(3)).isEqualTo(3);
  }

  @Test
  void aSourceThatMetNoLimitSuggestsTheConfiguredSize() {
    assertThat(source.logs(1, 95)).hasSize(9);

    assertThat(source.suggestedBatchSize(500)).isEqualTo(500);
    assertThat(node.rejectedRangeCount()).isZero();
  }

  @Test
  void aLimitOfOneBlockStillWorks() {
    node.maxLogRange(1);

    List<RawLog> logs = source.logs(1, 95);

    assertThat(logs).hasSize(9);
    assertThat(source.suggestedBatchSize(500)).isEqualTo(1);
  }

  @Test
  void aNodeThatRejectsEveryRangeEndsInAChainRpcException() {
    node.maxLogRange(0);

    assertThatThrownBy(() -> source.logs(1, 95))
        .isInstanceOf(ChainRpcException.class)
        .hasMessageContaining("eth_getLogs returned error");
  }

  @Test
  void aNodeThatIsDownIsNotAskedAgainWithSmallerRanges() {
    node.down(true);

    assertThatThrownBy(() -> source.logs(1, 95)).isInstanceOf(ChainRpcException.class);

    assertThat(node.requests()).hasSize(1);
  }
}
