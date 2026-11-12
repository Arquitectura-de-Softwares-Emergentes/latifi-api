package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.ledger.domain.LoanQuery;
import com.latifi.api.ledger.domain.LoanSort;
import com.latifi.api.ledger.domain.LoanStatus;
import com.latifi.api.ledger.infrastructure.LoanQuerySql;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LoanQuerySqlTest {

  private static final String SENTINEL = "'; drop table loan; --";

  private static LoanQuery plain(LoanSort sort) {
    return new LoanQuery(null, null, null, null, null, null, null, sort, 0, 20);
  }

  @ParameterizedTest
  @EnumSource(LoanSort.class)
  void everySortUsesExactlyItsFixedFragmentAndEndsWithTheLoanIdTieBreak(LoanSort sort) {
    String sql = LoanQuerySql.page(plain(sort)).sql();

    assertThat(sql).contains("order by " + sort.orderBy() + "\nlimit :limit offset :offset");
    assertThat(sort.orderBy()).endsWith("l.loan_id desc");
    for (LoanSort other : LoanSort.values()) {
      if (other != sort && !sort.orderBy().contains(other.orderBy())) {
        assertThat(sql).doesNotContain(other.orderBy());
      }
    }
  }

  @Test
  void theFragmentsAreTheOnesOfTheContract() {
    assertThat(LoanSort.REPUTATION.orderBy())
        .isEqualTo("coalesce(rs.score, 500) desc, l.loan_id desc");
    assertThat(LoanSort.AMOUNT.orderBy()).isEqualTo("l.principal desc, l.loan_id desc");
    assertThat(LoanSort.TERM.orderBy()).isEqualTo("l.term_days asc, l.loan_id desc");
    assertThat(LoanSort.RATE.orderBy()).isEqualTo("l.interest_rate_bps desc, l.loan_id desc");
    assertThat(LoanSort.RECENT.orderBy()).isEqualTo("l.requested_at desc, l.loan_id desc");
    assertThat(LoanSort.NEXT_DUE.orderBy())
        .isEqualTo("l.next_due_date asc nulls last, l.loan_id desc");
  }

  @Test
  void aQueryWithoutFiltersHasNoWhereClause() {
    LoanQuerySql.Statement page = LoanQuerySql.page(plain(LoanSort.REPUTATION));

    assertThat(page.sql()).doesNotContain("where");
    assertThat(page.params()).containsOnlyKeys("limit", "offset");
  }

  @Test
  void eachFilterAddsItsConditionOnlyWhenPresent() {
    assertCondition(
        new LoanQuery(LoanStatus.OPEN, null, null, null, null, null, null, LoanSort.AMOUNT, 0, 20),
        "l.status = :status",
        "status");
    assertCondition(
        new LoanQuery(null, BigInteger.TEN, null, null, null, null, null, LoanSort.AMOUNT, 0, 20),
        "l.principal >= :minAmount",
        "minAmount");
    assertCondition(
        new LoanQuery(null, null, BigInteger.TEN, null, null, null, null, LoanSort.AMOUNT, 0, 20),
        "l.principal <= :maxAmount",
        "maxAmount");
    assertCondition(
        new LoanQuery(null, null, null, 30, null, null, null, LoanSort.AMOUNT, 0, 20),
        "l.term_days <= :maxTermDays",
        "maxTermDays");
    assertCondition(
        new LoanQuery(null, null, null, null, 2, null, null, LoanSort.AMOUNT, 0, 20),
        "coalesce(rs.level_rank, 0) >= :minLevelRank",
        "minLevelRank");
    assertCondition(
        new LoanQuery(null, null, null, null, null, "0xab", null, LoanSort.AMOUNT, 0, 20),
        "l.borrower = :borrower",
        "borrower");
    assertCondition(
        new LoanQuery(null, null, null, null, null, null, "0xcd", LoanSort.AMOUNT, 0, 20),
        "l.lender = :lender",
        "lender");
  }

  private static void assertCondition(LoanQuery query, String condition, String parameter) {
    LoanQuerySql.Statement page = LoanQuerySql.page(query);

    assertThat(page.sql()).contains("where " + condition + "\n");
    assertThat(page.params()).containsKey(parameter).containsKeys("limit", "offset");
    assertThat(page.params()).hasSize(3);
  }

  @Test
  void filtersCombineWithAnd() {
    LoanQuery query =
        new LoanQuery(
            LoanStatus.ACTIVE,
            BigInteger.ONE,
            BigInteger.TEN,
            45,
            1,
            "0xab",
            "0xcd",
            LoanSort.RATE,
            0,
            20);

    String sql = LoanQuerySql.page(query).sql();

    assertThat(sql)
        .contains(
            """
            where l.status = :status
              and l.principal >= :minAmount
              and l.principal <= :maxAmount
              and l.term_days <= :maxTermDays
              and coalesce(rs.level_rank, 0) >= :minLevelRank
              and l.borrower = :borrower
              and l.lender = :lender
            """);
  }

  @Test
  void amountsGoInAsNumericParameters() {
    BigInteger huge = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    LoanQuery query =
        new LoanQuery(null, huge, huge, null, null, null, null, LoanSort.AMOUNT, 0, 20);

    LoanQuerySql.Statement page = LoanQuerySql.page(query);

    assertThat(page.params().get("minAmount")).isEqualTo(new BigDecimal(huge));
    assertThat(page.params().get("maxAmount")).isEqualTo(new BigDecimal(huge));
    assertThat(page.sql()).doesNotContain(huge.toString());
  }

  @Test
  void textFilterValuesNeverEnterTheSqlTextAndAlwaysEndUpInTheParameters() {
    LoanQuery query =
        new LoanQuery(null, null, null, null, null, SENTINEL, SENTINEL, LoanSort.REPUTATION, 0, 20);

    LoanQuerySql.Statement page = LoanQuerySql.page(query);
    LoanQuerySql.Statement count = LoanQuerySql.count(query);

    for (LoanQuerySql.Statement statement : new LoanQuerySql.Statement[] {page, count}) {
      assertThat(statement.sql()).doesNotContain(SENTINEL).doesNotContain("drop table");
      assertThat(statement.params()).containsEntry("borrower", SENTINEL);
      assertThat(statement.params()).containsEntry("lender", SENTINEL);
    }
  }

  @Test
  void pagingUsesBoundLimitAndOffset() {
    LoanQuery query =
        new LoanQuery(null, null, null, null, null, null, null, LoanSort.RECENT, 3, 25);

    LoanQuerySql.Statement page = LoanQuerySql.page(query);

    assertThat(page.params()).containsEntry("limit", 25).containsEntry("offset", 75L);
    assertThat(page.sql()).doesNotContain("75").doesNotContain("25");
  }

  @Test
  void theOffsetOfADeepPageDoesNotOverflow() {
    LoanQuery query =
        new LoanQuery(
            null, null, null, null, null, null, null, LoanSort.RECENT, Integer.MAX_VALUE, 100);

    assertThat(LoanQuerySql.page(query).params())
        .containsEntry("offset", (long) Integer.MAX_VALUE * 100);
  }

  @Test
  void theCountHasTheSameConditionsAndNoOrderOrLimit() {
    LoanQuery query =
        new LoanQuery(
            LoanStatus.OPEN, BigInteger.ONE, null, 30, 2, "0xab", null, LoanSort.TERM, 2, 10);

    LoanQuerySql.Statement page = LoanQuerySql.page(query);
    LoanQuerySql.Statement count = LoanQuerySql.count(query);

    String conditions =
        page.sql().substring(page.sql().indexOf("where "), page.sql().indexOf("order by"));
    assertThat(count.sql()).startsWith("select count(*)").endsWith(conditions);
    assertThat(count.sql())
        .doesNotContain("order by")
        .doesNotContain("limit")
        .doesNotContain("offset");
    assertThat(count.params()).doesNotContainKeys("limit", "offset");
    assertThat(page.params().keySet()).containsAll(count.params().keySet());
  }
}
