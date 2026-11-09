package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.FakeChainNode;
import com.latifi.api.support.TestBeansConfig.RecordingListener;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps of the loan mirror feature. The indexer itself is driven by the steps of {@link
 * IndexerSteps}; these steps emit the lifecycle logs with their own terms and read the {@code loan}
 * table.
 */
public class LoanMirrorSteps {

  private final FakeChainNode node;
  private final JdbcTemplate jdbc;
  private final RecordingListener listener;

  private int emitted;
  private List<Map<String, String>> notedRows;

  public LoanMirrorSteps(FakeChainNode node, JdbcTemplate jdbc, RecordingListener listener) {
    this.node = node;
    this.jdbc = jdbc;
    this.listener = listener;
  }

  @Given(
      "a LoanRequested log for loan {int} of {long} units at {int} basis points over {int} days in {int} installments is emitted at block {int}")
  public void requested(
      int loan, long principal, int rateBps, int termDays, int installments, int block) {
    emitted++;
    node.emit(
        block,
        ChainFixtures.loanRequested(
            hash(),
            emitted,
            BigInteger.valueOf(loan),
            ChainFixtures.BORROWER,
            BigInteger.valueOf(principal),
            rateBps,
            termDays,
            installments));
  }

  @Given(
      "a LoanFunded log for loan {int} at the funding time {long} with the due date {long} is emitted at block {int}")
  public void funded(int loan, long fundedAt, long dueDate, int block) {
    emitted++;
    node.emit(
        block,
        ChainFixtures.loanFunded(
            hash(),
            emitted,
            BigInteger.valueOf(loan),
            ChainFixtures.LENDER,
            ChainFixtures.BORROWER,
            BigInteger.ONE,
            fundedAt,
            dueDate));
  }

  @Given("a LoanRepaid log for loan {int} of {long} units is emitted at block {int}")
  public void repaid(int loan, long amount, int block) {
    emitted++;
    node.emit(
        block,
        ChainFixtures.loanRepaid(
            hash(), emitted, BigInteger.valueOf(loan), BigInteger.valueOf(amount), true));
  }

  @When("the loan rows are noted")
  public void noteRows() {
    notedRows = rows();
  }

  @Then("the loan table has {int} row(s)")
  public void rowCount(int expected) {
    assertThat(jdbc.queryForObject("select count(*) from loan", Integer.class)).isEqualTo(expected);
  }

  @Then("there is no loan row for loan {int}")
  public void noRow(int loan) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from loan where loan_id = ?", Integer.class, (long) loan))
        .isZero();
  }

  /**
   * Compares the named columns of one loan row with the expected text. Amounts are plain decimal
   * text, times are Unix seconds, and an empty column reads {@code null}.
   */
  @Then("the loan {int} has these values:")
  public void loanHasValues(int loan, DataTable table) {
    Map<String, String> row = row(loan);
    Map<String, String> actual = new LinkedHashMap<>();
    Map<String, String> expected = table.asMap(String.class, String.class);
    expected.keySet().forEach(column -> actual.put(column, row.get(column)));
    assertThat(actual).containsExactlyEntriesOf(expected);
  }

  @Then("the loan {int} was requested at the timestamp of block {int}")
  public void requestedAtBlock(int loan, int block) {
    assertThat(row(loan).get("requested_at")).isEqualTo(String.valueOf(node.timestampOf(block)));
  }

  @Then("the last listener batch lists the borrower among the affected borrowers")
  public void batchListsBorrower() {
    assertThat(listener.deltas()).isNotEmpty();
    assertThat(listener.deltas().get(listener.deltas().size() - 1).affectedBorrowers())
        .contains(ChainFixtures.BORROWER);
  }

  @Then("the loan rows are the same as noted")
  public void rowsUnchanged() {
    assertThat(rows()).isEqualTo(notedRows);
  }

  private String hash() {
    return ChainFixtures.txHash(5_000L + emitted);
  }

  private Map<String, String> row(int loan) {
    List<Map<String, Object>> found =
        jdbc.queryForList("select * from loan where loan_id = ?", (long) loan);
    assertThat(found).as("the loan row of loan %d", loan).hasSize(1);
    return text(found.get(0));
  }

  private List<Map<String, String>> rows() {
    List<Map<String, String>> out = new ArrayList<>();
    for (Map<String, Object> row : jdbc.queryForList("select * from loan order by loan_id")) {
      out.add(text(row));
    }
    return out;
  }

  private static Map<String, String> text(Map<String, Object> row) {
    Map<String, String> out = new LinkedHashMap<>();
    row.forEach((column, value) -> out.put(column, text(value)));
    return out;
  }

  private static String text(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof BigDecimal number) {
      return number.toPlainString();
    }
    if (value instanceof Timestamp time) {
      return String.valueOf(time.toInstant().getEpochSecond());
    }
    return value.toString().trim();
  }
}
