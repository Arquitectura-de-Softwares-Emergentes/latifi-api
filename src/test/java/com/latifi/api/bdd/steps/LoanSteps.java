package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.bdd.support.Wallets;
import com.latifi.api.ledger.domain.LoanSchedule;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.ContractAssertions;
import com.latifi.api.support.FakeChainNode;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.web3j.crypto.Keys;

/**
 * Steps of the loan feed feature. The data helper emits six loans from four borrowers through the
 * simulated node, so the scores of the borrowers come from the real indexer and reputation
 * projector. Wallets and sessions come from {@link ProfileSteps}, the indexer runs through {@link
 * IndexerSteps}.
 *
 * <p>The six loans (id, borrower, principal in base units, rate, days, installments, state):
 *
 * <pre>
 * 1  NORMAL  500000000  1200  30  1  OPEN
 * 2  GHOST  2000000000   800  90  3  OPEN
 * 3  MID    1000000000  1000  60  2  ACTIVE, one installment paid on time
 * 4  TOP    1500000000  1500  90  3  REPAID, three installments paid on time
 * 5  MID     250000000  2000  15  1  OPEN
 * 6  TOP    3000000000   500 120  4  ACTIVE, nothing paid
 * </pre>
 *
 * NORMAL has score 500 and level NEW. GHOST has the same score, and its reputation row is removed
 * after the run so the feed has to apply the defaults. MID has a profile and one on-time
 * installment, so 570 and BUILDING. TOP has a profile and three on-time installments, so 610 and
 * TRUSTED.
 */
public class LoanSteps {

  private static final String API = "/api/v1/loans";
  private static final String GHOST = "0x3333333333333333333333333333333333333333";
  private static final long FUNDED_AT = 1_800_000_000L;
  private static final long DAY = 86_400L;

  /** The terms of an emitted loan, kept to compute installment amounts. */
  private record Terms(BigInteger principal, int rateBps, int termDays, int installments) {

    LoanSchedule schedule() {
      return new LoanSchedule(principal, rateBps, termDays, installments, FUNDED_AT);
    }
  }

  private final ApiClient api;
  private final FakeChainNode node;
  private final JdbcTemplate jdbc;
  private final Wallets wallets;

  private final Map<Integer, Terms> terms = new HashMap<>();
  private final Map<Integer, String> borrowers = new HashMap<>();
  private final List<Long> collectedIds = new ArrayList<>();
  private final Set<Long> totals = new HashSet<>();
  private int emitted;
  private String lastPath;

  public LoanSteps(ApiClient api, FakeChainNode node, JdbcTemplate jdbc, Wallets wallets) {
    this.api = api;
    this.node = node;
    this.jdbc = jdbc;
    this.wallets = wallets;
  }

  // ---------------------------------------------------------------- chain data

  @Given("the six feed loans are emitted for the borrowers {word} and {word}")
  public void sixFeedLoans(String mid, String top) {
    String midAddress = wallet(mid).address();
    String topAddress = wallet(top).address();

    request(1, ChainFixtures.BORROWER, 500_000_000L, 1200, 30, 1, 10);
    request(2, GHOST, 2_000_000_000L, 800, 90, 3, 20);

    request(3, midAddress, 1_000_000_000L, 1000, 60, 2, 30);
    fund(3, 32);
    payInstallments(3, 1, 34);

    request(4, topAddress, 1_500_000_000L, 1500, 90, 3, 40);
    fund(4, 42);
    payInstallments(4, 3, 44);

    request(5, midAddress, 250_000_000L, 2000, 15, 1, 50);

    request(6, topAddress, 3_000_000_000L, 500, 120, 4, 60);
    fund(6, 62);
  }

  /** Deletes the reputation row, so the feed shows the defaults of a borrower without one. */
  @Given("the reputation row of the borrower of loan {int} is removed")
  public void reputationRowRemoved(int loan) {
    int removed =
        jdbc.update("delete from reputation_state where address = ?", borrowers.get(loan));
    assertThat(removed).as("the reputation row of loan %d", loan).isEqualTo(1);
  }

  // ---------------------------------------------------------------- reads

  /**
   * Reads the feed as the named wallet. The query is written as {@code name=value&name=value}.
   * Values are URL encoded, so an injection probe travels as plain text. A name in braces stands
   * for the address of that wallet, {@code NAME_CHECKSUM} for its checksummed form, {@code LENDER}
   * and {@code LENDER_UPPER} for the lender of the scenarios.
   */
  @When("the wallet {word} reads the loan feed with the query {string}")
  public void walletReadsFeed(String reader, String query) {
    get(wallets.tokens().get(reader), API, query);
  }

  @When("an anonymous client reads the loan feed")
  public void anonymousReadsFeed() {
    get(null, API, "");
  }

  @When("the wallet {word} reads the loan {string}")
  public void walletReadsLoan(String reader, String loanId) {
    get(wallets.tokens().get(reader), API + "/" + loanId, "");
  }

  @When("an anonymous client reads the loan {string}")
  public void anonymousReadsLoan(String loanId) {
    get(null, API + "/" + loanId, "");
  }

  /** Reads one page of the feed and remembers its ids, to check paging across several calls. */
  @When("the wallet {word} reads page {int} of the feed with the query {string}")
  public void walletReadsPage(String reader, int page, String query) {
    String joined = (query.isEmpty() ? "" : query + "&") + "page=" + page;
    get(wallets.tokens().get(reader), API, joined);
    assertThat(api.status()).as("body: %s", api.body()).isEqualTo(200);
    ContractAssertions.assertResponseConforms(
        Request.Method.GET, lastPath, api.status(), api.header("content-type"), api.body());
    api.json().get("items").forEach(item -> collectedIds.add(item.get("loanId").asLong()));
    totals.add(api.json().get("totalItems").asLong());
  }

  // ---------------------------------------------------------------- checks

  @Then("the loan response conforms to the API contract")
  public void conforms() {
    ContractAssertions.assertResponseConforms(
        Request.Method.GET, lastPath, api.status(), api.header("content-type"), api.body());
  }

  @Then("the feed lists the loans {string}")
  public void feedLists(String ids) {
    assertThat(feedIds()).as("body: %s", api.body()).isEqualTo(expectedIds(ids));
  }

  @Then("the feed reports page {int} of size {int} with {int} total items")
  public void feedReports(int page, int size, int total) {
    JsonNode body = api.json();
    assertThat(body.get("page").asInt()).isEqualTo(page);
    assertThat(body.get("size").asInt()).isEqualTo(size);
    assertThat(body.get("totalItems").asLong()).as("body: %s", api.body()).isEqualTo(total);
  }

  @Then("the feed shows these borrower reputations:")
  public void feedShowsReputations(DataTable table) {
    Map<Long, JsonNode> byLoan = new HashMap<>();
    api.json().get("items").forEach(item -> byLoan.put(item.get("loanId").asLong(), item));
    for (Map<String, String> row : table.asMaps()) {
      long loan = Long.parseLong(row.get("loan"));
      JsonNode item = byLoan.get(loan);
      assertThat(item).as("loan %d in %s", loan, api.body()).isNotNull();
      assertThat(item.get("borrowerReputation").get("score").asInt())
          .as("score of loan %d", loan)
          .isEqualTo(Integer.parseInt(row.get("score")));
      assertThat(item.get("borrowerReputation").get("level").asText())
          .as("level of loan %d", loan)
          .isEqualTo(row.get("level"));
    }
    assertThat(byLoan).hasSize(table.asMaps().size());
  }

  @Then("every feed item has a total due")
  public void everyItemHasTotalDue() {
    api.json()
        .get("items")
        .forEach(
            item ->
                assertThat(item.get("totalDue").asText())
                    .as("loan %s", item.get("loanId").asText())
                    .matches("[0-9]{1,78}"));
  }

  @Then("the pages together list the loans {string} without a repeat")
  public void pagesTogether(String ids) {
    assertThat(collectedIds).isEqualTo(expectedIds(ids));
    assertThat(new HashSet<>(collectedIds)).hasSameSizeAs(collectedIds);
  }

  @Then("every page reported {int} total items")
  public void everyPageReported(int total) {
    assertThat(totals).containsExactly((long) total);
  }

  @Then(
      "the loan response has the status {word}, the total due {string} and the amount repaid {string}")
  public void loanAmounts(String status, String totalDue, String repaid) {
    JsonNode body = api.json();
    assertThat(body.get("status").asText()).as("body: %s", api.body()).isEqualTo(status);
    assertThat(body.get("totalDue").asText()).isEqualTo(totalDue);
    assertThat(body.get("amountRepaid").asText()).isEqualTo(repaid);
  }

  @Then(
      "the first feed item shows the status {word}, the total due {string} and the amount repaid {string}")
  public void firstItemAmounts(String status, String totalDue, String repaid) {
    JsonNode item = api.json().get("items").get(0);
    assertThat(item.get("status").asText()).isEqualTo(status);
    assertThat(item.get("totalDue").asText()).isEqualTo(totalDue);
    assertThat(item.get("amountRepaid").asText()).isEqualTo(repaid);
  }

  @Then("the loan response has the field {word} with the value {string}")
  public void loanField(String field, String value) {
    assertThat(api.json().get(field)).as("field %s of %s", field, api.body()).isNotNull();
    assertThat(api.json().get(field).asText()).isEqualTo(value);
  }

  @Then("the loan response has no field {word}")
  public void loanHasNoField(String field) {
    assertThat(api.json().has(field)).as("field %s of %s", field, api.body()).isFalse();
  }

  @Then("the loan response has the time {word} at {long}")
  public void loanTime(String field, long epochSeconds) {
    assertThat(api.json().get(field)).as("field %s of %s", field, api.body()).isNotNull();
    assertThat(Instant.parse(api.json().get(field).asText()))
        .isEqualTo(Instant.ofEpochSecond(epochSeconds));
  }

  @Then("the loan response shows the borrower reputation {int} and {word}")
  public void loanReputation(int score, String level) {
    JsonNode reputation = api.json().get("borrowerReputation");
    assertThat(reputation.get("score").asInt()).isEqualTo(score);
    assertThat(reputation.get("level").asText()).isEqualTo(level);
  }

  // ---------------------------------------------------------------- helpers

  private void request(
      int loan,
      String borrower,
      long principal,
      int rateBps,
      int termDays,
      int installments,
      int block) {
    terms.put(loan, new Terms(BigInteger.valueOf(principal), rateBps, termDays, installments));
    borrowers.put(loan, borrower);
    emitted++;
    node.emit(
        block,
        ChainFixtures.loanRequested(
            hash(),
            emitted,
            BigInteger.valueOf(loan),
            borrower,
            BigInteger.valueOf(principal),
            rateBps,
            termDays,
            installments));
  }

  private void fund(int loan, int block) {
    Terms t = terms.get(loan);
    emitted++;
    node.emit(
        block,
        ChainFixtures.loanFunded(
            hash(),
            emitted,
            BigInteger.valueOf(loan),
            ChainFixtures.LENDER,
            borrowers.get(loan),
            t.principal(),
            FUNDED_AT,
            FUNDED_AT + t.termDays() * DAY));
  }

  /** Pays the first {@code count} installments of the loan on time, one per block. */
  private void payInstallments(int loan, int count, int firstBlock) {
    LoanSchedule schedule = terms.get(loan).schedule();
    for (int k = 1; k <= count; k++) {
      BigInteger before = k == 1 ? BigInteger.ZERO : schedule.target(k - 1);
      BigInteger amount = schedule.target(k).subtract(before);
      emitted++;
      node.emit(
          firstBlock + 2 * (k - 1),
          ChainFixtures.loanRepaid(hash(), emitted, BigInteger.valueOf(loan), amount, true));
    }
  }

  private void get(String token, String path, String query) {
    lastPath = path;
    api.withBearer(token);
    api.get(query.isEmpty() ? path : path + "?" + encode(substitute(query)));
  }

  /** Replaces the wallet placeholders of a query with real addresses. */
  private String substitute(String query) {
    String out =
        query
            .replace("{LENDER}", ChainFixtures.LENDER)
            .replace("{LENDER_UPPER}", "0x" + ChainFixtures.LENDER.substring(2).toUpperCase());
    for (Map.Entry<String, TestWallet> entry : wallets.wallets().entrySet()) {
      out =
          out.replace("{" + entry.getKey() + "}", entry.getValue().address())
              .replace(
                  "{" + entry.getKey() + "_CHECKSUM}",
                  Keys.toChecksumAddress(entry.getValue().address()));
    }
    return out;
  }

  /** Encodes the value of every {@code name=value} pair. */
  private static String encode(String query) {
    List<String> pairs = new ArrayList<>();
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals < 0) {
        pairs.add(URLEncoder.encode(pair, StandardCharsets.UTF_8));
      } else {
        pairs.add(
            pair.substring(0, equals)
                + "="
                + URLEncoder.encode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
    }
    return String.join("&", pairs);
  }

  private List<Long> feedIds() {
    List<Long> ids = new ArrayList<>();
    api.json().get("items").forEach(item -> ids.add(item.get("loanId").asLong()));
    return ids;
  }

  private static List<Long> expectedIds(String ids) {
    if (ids.isBlank()) {
      return List.of();
    }
    List<Long> out = new ArrayList<>();
    for (String id : ids.split(",")) {
      out.add(Long.parseLong(id.trim()));
    }
    return out;
  }

  private TestWallet wallet(String name) {
    TestWallet wallet = wallets.wallets().get(name);
    assertThat(wallet).as("wallet %s", name).isNotNull();
    return wallet;
  }

  private String hash() {
    return ChainFixtures.txHash(8_000L + emitted);
  }
}
