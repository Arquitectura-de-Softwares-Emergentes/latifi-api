package com.latifi.api.ledger.infrastructure;

import com.latifi.api.ledger.domain.LoanPageResult;
import com.latifi.api.ledger.domain.LoanQuery;
import com.latifi.api.ledger.domain.LoanQueryRepository;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanStatus;
import com.latifi.api.ledger.domain.LoanView;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads loans joined with the reputation of their borrower. A borrower without a row in {@code
 * reputation_state} gets score 500 and level NEW. Every value goes in as a bound parameter.
 */
@Repository
public class JdbcLoanQueryRepository implements LoanQueryRepository {

  private final JdbcClient jdbc;

  public JdbcLoanQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<LoanView> find(BigInteger loanId) {
    return jdbc.sql(LoanQuerySql.SELECT + " where l.loan_id = :loanId")
        .param("loanId", new BigDecimal(loanId))
        .query(JdbcLoanQueryRepository::view)
        .optional();
  }

  @Override
  public LoanPageResult search(LoanQuery query) {
    LoanQuerySql.Statement page = LoanQuerySql.page(query);
    List<LoanView> items =
        jdbc.sql(page.sql()).params(page.params()).query(JdbcLoanQueryRepository::view).list();
    LoanQuerySql.Statement count = LoanQuerySql.count(query);
    Long total = jdbc.sql(count.sql()).params(count.params()).query(Long.class).single();
    return new LoanPageResult(items, query.page(), query.size(), total);
  }

  static LoanView view(ResultSet rs, int row) throws SQLException {
    String lender = rs.getString("lender");
    BigDecimal totalDue = rs.getBigDecimal("total_due");
    LoanState state =
        new LoanState(
            rs.getBigDecimal("loan_id").toBigIntegerExact(),
            rs.getString("borrower").trim(),
            lender == null ? null : lender.trim(),
            rs.getBigDecimal("principal").toBigIntegerExact(),
            rs.getInt("interest_rate_bps"),
            rs.getInt("term_days"),
            rs.getInt("installments"),
            LoanStatus.valueOf(rs.getString("status")),
            totalDue == null ? null : totalDue.toBigIntegerExact(),
            rs.getBigDecimal("total_paid").toBigIntegerExact(),
            rs.getInt("installments_paid"),
            instant(rs, "requested_at"),
            instant(rs, "funded_at"),
            instant(rs, "final_due_date"),
            instant(rs, "next_due_date"));
    return new LoanView(state, rs.getInt("borrower_score"), rs.getString("borrower_level"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
