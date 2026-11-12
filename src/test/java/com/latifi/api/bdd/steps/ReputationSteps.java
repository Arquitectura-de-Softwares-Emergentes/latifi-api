package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.bdd.support.Wallets;
import com.latifi.api.ledger.domain.LoanSchedule;
import com.latifi.api.reputation.application.ReputationProjector;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.ContractAssertions;
import com.latifi.api.support.FakeChainNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Steps of the reputation feature. The loan logs are emitted with explicit funding and block times,
 * so the scenarios fix the due dates and the days late exactly. The indexer runs through the steps
 * of {@link IndexerSteps}, wallets and sessions come from {@link ProfileSteps}.
 */
public class ReputationSteps {

  private static final String API = "/api/v1/reputation/";
  private static final int RATE_BPS = 1000;
  private static final long DAY = 86_400L;

  /** The terms of a loan that a scenario requested, kept to compute due dates and totals. */
  private record Terms(
      BigInteger principal, int termDays, int installments, long fundedAt, String borrower) {

    Terms funded(long time) {
      return new Terms(principal, termDays, installments, time, borrower);
    }

    LoanSchedule schedule() {
      return new LoanSchedule(principal, RATE_BPS, termDays, installments, fundedAt);
    }
  }

  private final ApiClient api;
  private final FakeChainNode node;
  private final JdbcTemplate jdbc;
  private final ApplicationContext context;
  private final Wallets wallets;

  private final Map<Integer, Terms> loans = new HashMap<>();
  private String borrower = ChainFixtures.BORROWER;
  private int emitted;
  private String lastPath;
  private List<Long> rememberedIds = List.of();

  public ReputationSteps(
      ApiClient api,
      FakeChainNode node,
      JdbcTemplate jdbc,
      ApplicationContext context,
      Wallets wallets) {
    this.api = api;
    this.node = node;
    this.jdbc = jdbc;
    this.context = context;
    this.wallets = wallets;
  }

  // ---------------------------------------------------------------- chain data

  @Given("the borrower of the following loans is the wallet {word}")
  public void borrowerIsWallet(String name) {
    borrower = wallet(name).address();
  }

  @Given(
      "loan {int} of {long} units in {int} installment(s) over {int} days is requested at block {int}")
  public void requested(int loan, long principal, int installments, int termDays, int block) {
    loans.put(loan, new Terms(BigInteger.valueOf(principal), termDays, installments, 0, borrower));
    emitted++;
    node.emit(
        block,
        ChainFixtures.loanRequested(
            hash(),
            emitted,
            BigInteger.valueOf(loan),
            borrower,
            BigInteger.valueOf(principal),
            RATE_BPS,
            termDays,
            installments));
  }

  @Given("loan {int} is funded at the time {long} at block {int}")
  public void funded(int loan, long fundedAt, int block) {
    Terms terms = loans.get(loan).funded(fundedAt);
    loans.put(loan, terms);
    emitted++;
    node.emit(
        block,
        ChainFixtures.loanFunded(
            hash(),
            emitted,
            BigInteger.valueOf(loan),
            ChainFixtures.LENDER,
            terms.borrower(),
            BigInteger.ONE,
            fundedAt,
            fundedAt + terms.termDays() * DAY));
  }

  @Given(
      "a payment of {long} units on loan {int} is emitted at block {int} with the block time {long} and the flag {word}")
  public void payment(long amount, int loan, int block, long blockTime, String flag) {
    pay(loan, BigInteger.valueOf(amount), block, blockTime, flag);
  }

  @Given(
      "{int} payments of {long} units on loan {int} are emitted at block {int} with the block time {long} and the flag {word}")
  public void payments(int count, long amount, int loan, int block, long blockTime, String flag) {
    for (int i = 0; i < count; i++) {
      pay(loan, BigInteger.valueOf(amount), block, blockTime, flag);
    }
  }

  @Given(
      "loan {int} is repaid in full at block {int} with the block time {long} and the flag {word}")
  public void repaidInFull(int loan, int block, long blockTime, String flag) {
    pay(loan, loans.get(loan).schedule().totalDue(), block, blockTime, flag);
  }

  @Given("loan {int} defaults at block {int} with the block time {long}")
  public void defaults(int loan, int block, long blockTime) {
    emitted++;
    node.timestamp(block, blockTime);
    node.emit(
        block,
        ChainFixtures.loanDefaulted(
            hash(), emitted, BigInteger.valueOf(loan), loans.get(loan).borrower(), BigInteger.ONE));
  }

  /**
   * Requests, funds and repays on time several loans of one installment each. Loan k uses the
   * blocks {@code from + 3k} to {@code from + 3k + 2}, and its payment comes 100 seconds plus k
   * after the funding time.
   */
  @Given(
      "{int} loans of {long} units in one installment from loan {int} are requested, funded at the time {long} and repaid on time from block {int}")
  public void singleInstallmentLoans(
      int count, long principal, int firstLoan, long fundedAt, int fromBlock) {
    for (int k = 0; k < count; k++) {
      int loan = firstLoan + k;
      int block = fromBlock + 3 * k;
      requested(loan, principal, 1, 90, block);
      funded(loan, fundedAt, block + 1);
      repaidInFull(loan, block + 2, fundedAt + 100 + k, "onTime");
    }
  }

  /**
   * Five reputation entries for the wallet: the profile (saved by the scenario), three on-time
   * installments of loan 7 and one late loan 8, the late one newest.
   */
  @Given("three on-time installments and one late loan are indexed for the wallet {word}")
  public void fiveEntries(String name) {
    borrowerIsWallet(name);
    requested(7, 1_000_000_000L, 3, 90, 10);
    funded(7, 1_800_000_000L, 12);
    payment(341_552_511L, 7, 14, 1_802_500_000L, "onTime");
    payment(341_552_511L, 7, 15, 1_805_100_000L, "onTime");
    payment(341_552_512L, 7, 16, 1_807_700_000L, "onTime");
    requested(8, 1_000_000_000L, 1, 90, 20);
    funded(8, 1_800_000_000L, 22);
    repaidInFull(8, 24, 1_807_776_000L + 2 * DAY, "late");
  }

  // ---------------------------------------------------------------- reads

  @When("the wallet {word} reads the reputation of {string}")
  public void walletReads(String reader, String target) {
    get(wallets.tokens().get(reader), API + address(target), "");
  }

  @When("an anonymous client reads the reputation of {string}")
  public void anonymousReads(String target) {
    get(null, API + address(target), "");
  }

  @When("the wallet {word} reads the reputation history of {string} with the query {string}")
  public void walletReadsHistory(String reader, String target, String query) {
    get(wallets.tokens().get(reader), API + address(target) + "/history", query);
  }

  @When("an anonymous client reads the reputation history of {string}")
  public void anonymousReadsHistory(String target) {
    get(null, API + address(target) + "/history", "");
  }

  /** Runs the projector again for the borrower, as a batch that touched the borrower would. */
  @When("the reputation projection runs again for the borrower")
  public void projectionAgain() {
    context
        .getBean(TransactionTemplate.class)
        .executeWithoutResult(
            status -> context.getBean(ReputationProjector.class).recompute(borrower));
  }

  // ---------------------------------------------------------------- checks

  @Then("the reputation response conforms to the API contract")
  public void conforms() {
    ContractAssertions.assertResponseConforms(
        Request.Method.GET, lastPath, api.status(), api.header("content-type"), api.body());
  }

  @Then(
      "the reputation is score {int} and level {word} with {int} on time, {int} late and {int} defaults")
  public void reputationIs(int score, String level, int onTime, int late, int defaults) {
    JsonNode body = api.json();
    assertThat(body.get("score").asInt()).as("body: %s", api.body()).isEqualTo(score);
    assertThat(body.get("level").asText()).isEqualTo(level);
    assertThat(body.get("onTimeCount").asInt()).isEqualTo(onTime);
    assertThat(body.get("lateCount").asInt()).isEqualTo(late);
    assertThat(body.get("defaultCount").asInt()).isEqualTo(defaults);
  }

  @Then("the reputation has profileComplete {word}")
  public void profileComplete(String expected) {
    assertThat(api.json().get("profileComplete").asBoolean())
        .isEqualTo(Boolean.parseBoolean(expected));
  }

  @Then("the reputation was updated at the timestamp of block {int}")
  public void updatedAtBlock(int block) {
    assertThat(Instant.parse(api.json().get("updatedAt").asText()))
        .isEqualTo(Instant.ofEpochSecond(node.timestampOf(block)));
  }

  @Then(
      "the ledger row of the borrower carries the transaction hash and log index of the repayment of loan {int} and the score after {int}")
  public void ledgerRowMatchesEvent(int loan, int scoreAfter) {
    Map<String, Object> chain =
        jdbc.queryForMap(
            "select tx_hash, log_index from chain_event where event_type = 'LOAN_REPAID' and loan_id = ?",
            (long) loan);
    Map<String, Object> ledger =
        jdbc.queryForMap(
            "select tx_hash, log_index, score_after from reputation_event where address = ?",
            borrower);
    assertThat(((String) ledger.get("tx_hash")).trim())
        .isEqualTo(((String) chain.get("tx_hash")).trim());
    assertThat(ledger.get("log_index")).isEqualTo(chain.get("log_index"));
    assertThat(ledger.get("score_after")).isEqualTo(scoreAfter);
  }

  @Then("the history has {int} item(s) out of {int} in total")
  public void historyCount(int items, int total) {
    JsonNode body = api.json();
    assertThat(body.get("items")).as("body: %s", api.body()).hasSize(items);
    assertThat(body.get("totalItems").asLong()).isEqualTo(total);
  }

  @Then("the history item {int} is {word} with delta {int} and score after {int}")
  public void historyItem(int position, String type, int delta, int scoreAfter) {
    JsonNode item = item(position);
    assertThat(item.get("type").asText()).isEqualTo(type);
    assertThat(item.get("delta").asInt()).isEqualTo(delta);
    assertThat(item.get("scoreAfter").asInt()).isEqualTo(scoreAfter);
  }

  @Then("the history item {int} has {int} days late")
  public void historyDaysLate(int position, int days) {
    assertThat(item(position).get("daysLate").asInt()).isEqualTo(days);
  }

  @Then("the history item {int} carries a transaction hash and a log index")
  public void historyHasTransaction(int position) {
    JsonNode item = item(position);
    assertThat(item.get("txHash").asText()).matches("0x[0-9a-f]{64}");
    assertThat(item.get("logIndex").asInt()).isGreaterThanOrEqualTo(0);
  }

  @Then("the history item {int} has no transaction hash, no log index and no loan")
  public void historyHasNoTransaction(int position) {
    JsonNode item = item(position);
    assertThat(item.has("txHash")).isFalse();
    assertThat(item.has("logIndex")).isFalse();
    assertThat(item.has("loanId")).isFalse();
    assertThat(item.has("daysLate")).isFalse();
  }

  @Then("the history item types are {string}")
  public void historyTypes(String types) {
    List<String> actual = new ArrayList<>();
    api.json().get("items").forEach(item -> actual.add(item.get("type").asText()));
    assertThat(actual).isEqualTo(List.of(types.split(",")));
  }

  @Then("every history item continues the score of the next older item")
  public void scoresChain() {
    JsonNode items = api.json().get("items");
    for (int i = 0; i < items.size(); i++) {
      int before = items.get(i).get("scoreAfter").asInt() - items.get(i).get("delta").asInt();
      int expected = i + 1 < items.size() ? items.get(i + 1).get("scoreAfter").asInt() : 500;
      assertThat(before).as("item %d", i + 1).isEqualTo(expected);
      if (i + 1 < items.size()) {
        assertThat(Instant.parse(items.get(i).get("occurredAt").asText()))
            .isAfterOrEqualTo(Instant.parse(items.get(i + 1).get("occurredAt").asText()));
      }
    }
  }

  @Then("the history ids are remembered")
  public void rememberIds() {
    rememberedIds = ids();
  }

  @Then("the history ids are the same as remembered")
  public void sameIds() {
    assertThat(ids()).isEqualTo(rememberedIds).isNotEmpty();
  }

  @Then("the history ids are the remembered ids from position {int} to {int}")
  public void idsFromRemembered(int from, int to) {
    assertThat(ids()).isEqualTo(rememberedIds.subList(from - 1, to));
  }

  // ---------------------------------------------------------------- helpers

  private void pay(int loan, BigInteger amount, int block, long blockTime, String flag) {
    emitted++;
    node.timestamp(block, blockTime);
    node.emit(
        block,
        ChainFixtures.loanRepaid(
            hash(), emitted, BigInteger.valueOf(loan), amount, "onTime".equals(flag)));
  }

  private void get(String token, String path, String query) {
    lastPath = path;
    api.withBearer(token);
    api.get(query.isEmpty() ? path : path + "?" + query);
  }

  /** The borrower of the scenario, a wallet by name, or an address written out. */
  private String address(String target) {
    if ("borrower".equals(target)) {
      return borrower;
    }
    TestWallet wallet = wallets.wallets().get(target);
    return wallet != null ? wallet.address() : target;
  }

  private TestWallet wallet(String name) {
    TestWallet wallet = wallets.wallets().get(name);
    assertThat(wallet).as("wallet %s", name).isNotNull();
    return wallet;
  }

  private JsonNode item(int position) {
    JsonNode items = api.json().get("items");
    assertThat(items.size()).as("body: %s", api.body()).isGreaterThanOrEqualTo(position);
    return items.get(position - 1);
  }

  private List<Long> ids() {
    List<Long> ids = new ArrayList<>();
    api.json().get("items").forEach(item -> ids.add(item.get("id").asLong()));
    return ids;
  }

  private String hash() {
    return ChainFixtures.txHash(7_000L + emitted);
  }
}
