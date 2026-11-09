package com.latifi.api.ledger.infrastructure;

import com.latifi.api.ledger.domain.LoanQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link LoanQuery} into SQL text and named parameters. The text comes only from fixed
 * literals: the join, one fixed condition for each filter that is present, the ORDER BY fragment of
 * the sort enum, and {@code limit} and {@code offset} as named parameters. Every filter value goes
 * into the parameter map, so no request text ever becomes part of the statement. A borrower without
 * a row in {@code reputation_state} counts as score 500, level NEW and level rank 0.
 */
public final class LoanQuerySql {

  /** Every column of a loan plus the reputation of its borrower, with the defaults applied. */
  static final String SELECT =
      """
      select l.loan_id, l.borrower, l.lender, l.principal, l.interest_rate_bps, l.term_days,
             l.installments, l.status, l.total_due, l.total_paid, l.installments_paid,
             l.requested_at, l.funded_at, l.final_due_date, l.next_due_date,
             coalesce(rs.score, 500) as borrower_score,
             coalesce(rs.level, 'NEW') as borrower_level
      from loan l
      left join reputation_state rs on rs.address = l.borrower
      """;

  private static final String COUNT =
      """
      select count(*)
      from loan l
      left join reputation_state rs on rs.address = l.borrower
      """;

  /** A statement and the values of its named parameters. */
  public record Statement(String sql, Map<String, Object> params) {}

  private LoanQuerySql() {}

  /** The page statement: filters, the fixed order, then limit and offset. */
  public static Statement page(LoanQuery query) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(query, params);
    params.put("limit", query.size());
    params.put("offset", (long) query.page() * query.size());
    String sql =
        SELECT + where + "order by " + query.sort().orderBy() + "\nlimit :limit offset :offset";
    return new Statement(sql, params);
  }

  /** The count statement: the same conditions as the page, with no order, limit or offset. */
  public static Statement count(LoanQuery query) {
    Map<String, Object> params = new LinkedHashMap<>();
    return new Statement(COUNT + where(query, params), params);
  }

  private static String where(LoanQuery query, Map<String, Object> params) {
    List<String> conditions = new ArrayList<>();
    if (query.status() != null) {
      conditions.add("l.status = :status");
      params.put("status", query.status().name());
    }
    if (query.minAmount() != null) {
      conditions.add("l.principal >= :minAmount");
      params.put("minAmount", new BigDecimal(query.minAmount()));
    }
    if (query.maxAmount() != null) {
      conditions.add("l.principal <= :maxAmount");
      params.put("maxAmount", new BigDecimal(query.maxAmount()));
    }
    if (query.maxTermDays() != null) {
      conditions.add("l.term_days <= :maxTermDays");
      params.put("maxTermDays", query.maxTermDays());
    }
    if (query.minLevelRank() != null) {
      conditions.add("coalesce(rs.level_rank, 0) >= :minLevelRank");
      params.put("minLevelRank", query.minLevelRank());
    }
    if (query.borrower() != null) {
      conditions.add("l.borrower = :borrower");
      params.put("borrower", query.borrower());
    }
    if (query.lender() != null) {
      conditions.add("l.lender = :lender");
      params.put("lender", query.lender());
    }
    if (conditions.isEmpty()) {
      return "";
    }
    return "where " + String.join("\n  and ", conditions) + "\n";
  }
}
