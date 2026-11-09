package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.ledger.application.IndexerService;
import com.latifi.api.ledger.domain.CycleResult;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.FakeChainNode;
import com.latifi.api.support.TestBeansConfig.RecordingListener;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

public class IndexerSteps {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final FakeChainNode node;
  private final ApplicationContext context;
  private final JdbcTemplate jdbc;
  private final RecordingListener listener;

  private IndexerService indexer;
  private CycleResult lastResult;
  private String lastTxHash;
  private ChainFixtures.Log lastLog;
  private Long storedRowId;
  private int emitted;

  public IndexerSteps(
      FakeChainNode node,
      ApplicationContext context,
      JdbcTemplate jdbc,
      RecordingListener listener) {
    this.node = node;
    this.context = context;
    this.jdbc = jdbc;
    this.listener = listener;
    this.indexer = context.getBean(IndexerService.class);
  }

  @Given("the node has {int} blocks")
  public void nodeHasBlocks(int blocks) {
    node.mineTo(blocks);
  }

  @Given("a {word} log for loan {int} is emitted at block {int}")
  public void logEmitted(String eventName, int loan, int block) {
    emitted++;
    String txHash = ChainFixtures.txHash(1_000L + emitted);
    BigInteger loanId = BigInteger.valueOf(loan);
    ChainFixtures.Log log =
        switch (eventName) {
          case "LoanRequested" -> {
            lastTxHash = txHash;
            yield ChainFixtures.loanRequested(
                txHash,
                3,
                loanId,
                ChainFixtures.BORROWER,
                BigInteger.valueOf(5_000_000),
                1200,
                90,
                3);
          }
          case "LoanFunded" ->
              ChainFixtures.loanFunded(
                  txHash,
                  emitted,
                  loanId,
                  ChainFixtures.LENDER,
                  ChainFixtures.BORROWER,
                  BigInteger.valueOf(5_000_000),
                  1_760_000_100L,
                  1_767_776_100L);
          case "LoanRepaid" ->
              ChainFixtures.loanRepaid(
                  txHash, emitted, loanId, BigInteger.valueOf(1_800_000), true);
          case "LoanDefaulted" ->
              ChainFixtures.loanDefaulted(
                  txHash, emitted, loanId, ChainFixtures.BORROWER, BigInteger.valueOf(3_000_000));
          default -> throw new IllegalArgumentException("unknown event " + eventName);
        };
    lastLog = log;
    node.emit(block, log);
  }

  @Given("the node reorgs from block {int}")
  public void nodeReorgs(int block) {
    node.reorg(block);
  }

  @Given("the node mines to block {int}")
  public void nodeMinesTo(int block) {
    node.mineTo(block);
  }

  @Given("the last emitted log is emitted again at block {int} with the block timestamp {long}")
  public void lastLogEmittedAgain(int block, long epochSeconds) {
    node.timestamp(block, epochSeconds);
    node.emit(block, lastLog);
  }

  @Given("the node repeats every log in one answer")
  public void nodeRepeatsLogs() {
    node.repeatLogs(true);
  }

  @When("the indexer runs once")
  public void indexerRunsOnce() {
    lastResult = indexer.runOnce();
  }

  @When("the indexer restarts as a new service instance")
  public void indexerRestarts() {
    AutowireCapableBeanFactory factory = context.getAutowireCapableBeanFactory();
    indexer = factory.createBean(IndexerService.class);
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

  @Then("the cycle removed {int} event(s)")
  public void cycleRemoved(int expected) {
    assertThat(lastResult.eventsRemoved()).isEqualTo(expected);
  }

  @Then("the last listener batch touched the loans {string}")
  public void lastBatchTouched(String loans) {
    TreeSet<Long> expected = new TreeSet<>();
    Arrays.stream(loans.split(",")).map(Long::parseLong).forEach(expected::add);
    TreeSet<Long> actual = new TreeSet<>();
    listener
        .deltas()
        .get(listener.deltas().size() - 1)
        .touchedLoanIds()
        .forEach(id -> actual.add(id.longValueExact()));
    assertThat(actual).isEqualTo(expected);
  }

  @Then(
      "the stored row of the last emitted transaction sits at block {int} with the node hash and timestamp of that block")
  public void rowOfLastTransaction(int block) {
    Map<String, Object> row =
        jdbc.queryForMap("select * from chain_event where tx_hash = ?", lastLog.txHash());
    assertThat(row.get("block_number")).isEqualTo((long) block);
    assertThat(((String) row.get("block_hash")).trim()).isEqualTo(node.hashOf(block));
    assertThat(((Timestamp) row.get("block_timestamp")).toInstant().getEpochSecond())
        .isEqualTo(node.timestampOf(block));
  }

  @Given("the id of the chain_event row at block {int} is remembered")
  public void rememberRowId(int block) {
    storedRowId =
        jdbc.queryForObject(
            "select id from chain_event where block_number = ?", Long.class, (long) block);
  }

  @Then("the chain_event row at block {int} is the remembered one")
  public void rowIsTheRemembered(int block) {
    assertThat(
            jdbc.queryForObject(
                "select id from chain_event where block_number = ?", Long.class, (long) block))
        .isEqualTo(storedRowId);
  }

  @Then("the checkpoint is block {int}")
  public void checkpointIs(int block) {
    assertThat(jdbc.queryForObject("select last_block from indexer_checkpoint", Long.class))
        .isEqualTo((long) block);
  }

  @Then("chain_event holds the loans {string}")
  public void chainEventHoldsLoans(String loans) {
    List<Long> expected = Arrays.stream(loans.split(",")).map(Long::parseLong).sorted().toList();
    List<Long> actual =
        jdbc.queryForList("select loan_id from chain_event", java.math.BigDecimal.class).stream()
            .map(java.math.BigDecimal::longValueExact)
            .sorted()
            .toList();
    assertThat(actual).isEqualTo(expected);
  }

  @Then("no chain_event key appears twice")
  public void noDuplicateKeys() {
    List<Map<String, Object>> duplicates =
        jdbc.queryForList(
            "select tx_hash, log_index from chain_event group by tx_hash, log_index having count(*) > 1");
    assertThat(duplicates).isEmpty();
  }

  @Then(
      "the chain_event row of type {word} for loan {int} has the payload field {string} equal to {string}")
  public void payloadFieldEquals(String type, int loan, String field, String value) {
    String found =
        jdbc.queryForObject(
            "select payload ->> ? from chain_event where event_type = ? and loan_id = ?",
            String.class,
            field,
            type,
            loan);
    assertThat(found).isEqualTo(value);
  }

  @Then("the listener received {int} batches")
  public void listenerReceived(int batches) {
    assertThat(listener.deltas()).hasSize(batches);
  }

  @Then("batch {int} touched the loans {string}")
  public void batchTouched(int position, String loans) {
    TreeSet<Long> expected = new TreeSet<>();
    Arrays.stream(loans.split(",")).map(Long::parseLong).forEach(expected::add);
    TreeSet<Long> actual = new TreeSet<>();
    listener
        .deltas()
        .get(position - 1)
        .touchedLoanIds()
        .forEach(id -> actual.add(id.longValueExact()));
    assertThat(actual).isEqualTo(expected);
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
