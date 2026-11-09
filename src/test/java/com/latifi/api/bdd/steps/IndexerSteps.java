package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.ledger.application.IndexerService;
import com.latifi.api.ledger.domain.CycleResult;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.FakeChainNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public class IndexerSteps {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final FakeChainNode node;
  private final IndexerService indexer;
  private final JdbcTemplate jdbc;

  private CycleResult lastResult;
  private String lastTxHash;

  public IndexerSteps(FakeChainNode node, IndexerService indexer, JdbcTemplate jdbc) {
    this.node = node;
    this.indexer = indexer;
    this.jdbc = jdbc;
  }

  @Given("the node has {int} blocks")
  public void nodeHasBlocks(int blocks) {
    node.mineTo(blocks);
  }

  @Given("a LoanRequested log for loan {int} is emitted at block {int}")
  public void loanRequestedEmitted(int loanId, int block) {
    lastTxHash = ChainFixtures.txHash(1_000L + node.requests().size() + loanId * 100L + block);
    node.emit(
        block,
        ChainFixtures.loanRequested(
            lastTxHash,
            3,
            BigInteger.valueOf(loanId),
            ChainFixtures.BORROWER,
            BigInteger.valueOf(5_000_000),
            1200,
            90,
            3));
  }

  @When("the indexer runs once")
  public void indexerRunsOnce() {
    lastResult = indexer.runOnce();
  }

  @When("the node request log is cleared")
  public void requestLogCleared() {
    node.clearRequests();
  }

  @Then("chain_event has exactly {int} row(s)")
  public void chainEventCount(int expected) {
    assertThat(jdbc.queryForObject("select count(*) from chain_event", Integer.class))
        .isEqualTo(expected);
  }

  @Then(
      "the stored row is the LoanRequested log of loan {int} at block {int} with the block timestamp of the node")
  public void storedRowMatches(int loanId, int block) throws Exception {
    Map<String, Object> row = jdbc.queryForMap("select * from chain_event");
    assertThat(row.get("event_type")).isEqualTo("LOAN_REQUESTED");
    assertThat(((java.math.BigDecimal) row.get("loan_id")).toBigIntegerExact())
        .isEqualTo(BigInteger.valueOf(loanId));
    assertThat(((String) row.get("tx_hash")).trim()).isEqualTo(lastTxHash);
    assertThat(row.get("log_index")).isEqualTo(3);
    assertThat(row.get("block_number")).isEqualTo((long) block);
    assertThat(((String) row.get("block_hash")).trim()).isEqualTo(node.hashOf(block));
    assertThat(((Timestamp) row.get("block_timestamp")).toInstant().getEpochSecond())
        .isEqualTo(node.timestampOf(block));
    Map<String, String> payload =
        JSON.readValue(row.get("payload").toString(), new TypeReference<>() {});
    assertThat(payload)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "borrower", ChainFixtures.BORROWER,
                "amount", "5000000",
                "interestRateBps", "1200",
                "termDays", "90",
                "installments", "3"));
  }

  @Then("the checkpoint is block {int}")
  public void checkpointIs(int block) {
    assertThat(jdbc.queryForObject("select last_block from indexer_checkpoint", Long.class))
        .isEqualTo((long) block);
  }

  /** This is the proof of the real request shape: topics[0] is a nested array of alternatives. */
  @Then("the eth_getLogs filter sent the four topic0 values as one array of alternatives")
  public void filterShape() {
    String topics = node.requests("eth_getLogs").get(0).topicsJson();
    String expected = "[[\"" + String.join("\",\"", LoanBookEvents.ALL_TOPICS) + "\"]]";
    assertThat(topics).isEqualTo(expected);
  }

  @Then("the node served an eth_getLogs range that starts at block {int}")
  public void nodeServedRangeStartingAt(int block) {
    List<Long> starts =
        node.requests("eth_getLogs").stream().map(FakeChainNode.Request::from).toList();
    assertThat(starts).contains((long) block);
  }
}
