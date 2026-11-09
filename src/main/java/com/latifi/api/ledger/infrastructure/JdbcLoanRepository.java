package com.latifi.api.ledger.infrastructure;

import com.latifi.api.ledger.domain.LoanRepository;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanStatus;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the {@code loan} table. Every value goes in as a bound parameter. */
@Repository
public class JdbcLoanRepository implements LoanRepository {

  private final JdbcClient jdbc;

  public JdbcLoanRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsert(LoanState s) {
    jdbc.sql(
            """
            insert into loan
              (loan_id, borrower, lender, principal, interest_rate_bps, term_days, installments,
               status, total_due, total_paid, installments_paid, requested_at, funded_at,
               final_due_date, next_due_date)
            values
              (:loanId, :borrower, :lender, :principal, :rate, :termDays, :installments,
               :status, :totalDue, :totalPaid, :installmentsPaid, :requestedAt, :fundedAt,
               :finalDueDate, :nextDueDate)
            on conflict (loan_id) do update set
              borrower = excluded.borrower,
              lender = excluded.lender,
              principal = excluded.principal,
              interest_rate_bps = excluded.interest_rate_bps,
              term_days = excluded.term_days,
              installments = excluded.installments,
              status = excluded.status,
              total_due = excluded.total_due,
              total_paid = excluded.total_paid,
              installments_paid = excluded.installments_paid,
              requested_at = excluded.requested_at,
              funded_at = excluded.funded_at,
              final_due_date = excluded.final_due_date,
              next_due_date = excluded.next_due_date
            """)
        .param("loanId", decimal(s.loanId()))
        .param("borrower", s.borrower().toLowerCase(Locale.ROOT))
        .param(
            "lender",
            s.lender() == null ? null : s.lender().toLowerCase(Locale.ROOT),
            java.sql.Types.VARCHAR)
        .param("principal", decimal(s.principal()))
        .param("rate", s.interestRateBps())
        .param("termDays", s.termDays())
        .param("installments", (short) s.installments())
        .param("status", s.status().name())
        .param(
            "totalDue", s.totalDue() == null ? null : decimal(s.totalDue()), java.sql.Types.NUMERIC)
        .param("totalPaid", decimal(s.totalPaid()))
        .param("installmentsPaid", (short) s.installmentsPaid())
        .param("requestedAt", time(s.requestedAt()))
        .param("fundedAt", time(s.fundedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("finalDueDate", time(s.finalDueDate()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("nextDueDate", time(s.nextDueDate()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .update();
  }

  @Override
  public void delete(BigInteger loanId) {
    jdbc.sql("delete from loan where loan_id = :loanId").param("loanId", decimal(loanId)).update();
  }

  @Override
  public Optional<LoanState> find(BigInteger loanId) {
    return jdbc.sql(
            """
            select loan_id, borrower, lender, principal, interest_rate_bps, term_days,
                   installments, status, total_due, total_paid, installments_paid, requested_at,
                   funded_at, final_due_date, next_due_date
            from loan where loan_id = :loanId
            """)
        .param("loanId", decimal(loanId))
        .query(JdbcLoanRepository::state)
        .optional();
  }

  @Override
  public List<LoanState> findByBorrower(String borrower) {
    return jdbc.sql(
            """
            select loan_id, borrower, lender, principal, interest_rate_bps, term_days,
                   installments, status, total_due, total_paid, installments_paid, requested_at,
                   funded_at, final_due_date, next_due_date
            from loan where borrower = :borrower order by loan_id
            """)
        .param("borrower", borrower.toLowerCase(Locale.ROOT))
        .query(JdbcLoanRepository::state)
        .list();
  }

  private static LoanState state(ResultSet rs, int row) throws SQLException {
    String lender = rs.getString("lender");
    BigDecimal totalDue = rs.getBigDecimal("total_due");
    return new LoanState(
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
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static OffsetDateTime time(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
