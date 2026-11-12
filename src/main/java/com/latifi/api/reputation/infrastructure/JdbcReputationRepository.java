package com.latifi.api.reputation.infrastructure;

import com.latifi.api.reputation.domain.HistoryPage;
import com.latifi.api.reputation.domain.HistoryPage.Entry;
import com.latifi.api.reputation.domain.LedgerEntry;
import com.latifi.api.reputation.domain.ReputationLevel;
import com.latifi.api.reputation.domain.ReputationRepository;
import com.latifi.api.reputation.domain.ReputationResult;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.ReputationState;
import com.latifi.api.reputation.domain.SignalType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code reputation_event} and {@code reputation_state}. Every value goes in as a
 * bound parameter. The queries filter on the lowercase address that the callers already parsed.
 */
@Repository
public class JdbcReputationRepository implements ReputationRepository {

  private final JdbcClient jdbc;

  public JdbcReputationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lock(String address) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:address, 0))")
        .param("address", address)
        .query()
        .singleRow();
  }

  @Override
  public void replace(String address, ReputationResult result, Instant updatedAt) {
    for (LedgerEntry entry : result.entries()) {
      upsertEntry(address, entry);
    }
    List<String> keys = result.entries().stream().map(e -> e.signal().sourceKey()).toList();
    if (keys.isEmpty()) {
      jdbc.sql("delete from reputation_event where address = :address")
          .param("address", address)
          .update();
    } else {
      jdbc.sql(
              "delete from reputation_event where address = :address and source_key not in (:keys)")
          .param("address", address)
          .param("keys", keys)
          .update();
    }
    upsertState(address, result, updatedAt);
  }

  @Override
  public void delete(String address) {
    jdbc.sql("delete from reputation_event where address = :address")
        .param("address", address)
        .update();
    jdbc.sql("delete from reputation_state where address = :address")
        .param("address", address)
        .update();
  }

  @Override
  public Optional<ReputationState> findState(String address) {
    return jdbc.sql(
            """
            select address, score, level, on_time_count, late_count, default_count,
                   profile_complete, updated_at
            from reputation_state where address = :address
            """)
        .param("address", address)
        .query(JdbcReputationRepository::state)
        .optional();
  }

  @Override
  public HistoryPage history(String address, SignalType typeOrNull, int page, int size) {
    // The filter text is fixed here. The type value itself always travels as a bound parameter.
    String filter = typeOrNull == null ? "" : " and type = :type";
    JdbcClient.StatementSpec count =
        jdbc.sql("select count(*) from reputation_event where address = :address" + filter)
            .param("address", address);
    JdbcClient.StatementSpec rows =
        jdbc.sql(
                """
                select id, type, loan_id, delta, score_after, days_late, tx_hash, log_index,
                       occurred_at
                from reputation_event
                where address = :address
                """
                    + filter
                    + """

                    order by seq desc
                    limit :size offset :offset
                    """)
            .param("address", address)
            .param("size", size)
            .param("offset", (long) page * size);
    if (typeOrNull != null) {
      count = count.param("type", typeOrNull.name());
      rows = rows.param("type", typeOrNull.name());
    }
    long total = count.query(Long.class).single();
    List<Entry> items = rows.query(JdbcReputationRepository::entry).list();
    return new HistoryPage(items, page, size, total);
  }

  private void upsertEntry(String address, LedgerEntry entry) {
    ReputationSignal signal = entry.signal();
    jdbc.sql(
            """
            insert into reputation_event
              (address, source_key, seq, type, loan_id, delta, score_after, days_late,
               tx_hash, log_index, occurred_at)
            values
              (:address, :sourceKey, :seq, :type, :loanId, :delta, :scoreAfter, :daysLate,
               :txHash, :logIndex, :occurredAt)
            on conflict (address, source_key) do update set
              seq = excluded.seq,
              type = excluded.type,
              loan_id = excluded.loan_id,
              delta = excluded.delta,
              score_after = excluded.score_after,
              days_late = excluded.days_late,
              tx_hash = excluded.tx_hash,
              log_index = excluded.log_index,
              occurred_at = excluded.occurred_at
            """)
        .param("address", address)
        .param("sourceKey", signal.sourceKey())
        .param("seq", entry.seq())
        .param("type", signal.type().name())
        .param(
            "loanId",
            signal.loanId() == null ? null : new BigDecimal(new BigInteger(signal.loanId())),
            Types.NUMERIC)
        .param("delta", entry.delta())
        .param("scoreAfter", entry.scoreAfter())
        .param("daysLate", carriesDaysLate(signal.type()) ? signal.daysLate() : null, Types.INTEGER)
        .param("txHash", signal.txHash(), Types.CHAR)
        .param("logIndex", signal.txHash() == null ? null : signal.logIndex(), Types.INTEGER)
        .param("occurredAt", OffsetDateTime.ofInstant(signal.occurredAt(), ZoneOffset.UTC))
        .update();
  }

  /** Only late payments and defaults have a number of days late. */
  private static boolean carriesDaysLate(SignalType type) {
    return type == SignalType.REPAID_LATE || type == SignalType.DEFAULTED;
  }

  private void upsertState(String address, ReputationResult result, Instant updatedAt) {
    jdbc.sql(
            """
            insert into reputation_state
              (address, score, level, level_rank, on_time_count, late_count, default_count,
               profile_complete, updated_at)
            values
              (:address, :score, :level, :levelRank, :onTime, :late, :defaults,
               :profileComplete, :updatedAt)
            on conflict (address) do update set
              score = excluded.score,
              level = excluded.level,
              level_rank = excluded.level_rank,
              on_time_count = excluded.on_time_count,
              late_count = excluded.late_count,
              default_count = excluded.default_count,
              profile_complete = excluded.profile_complete,
              updated_at = excluded.updated_at
            """)
        .param("address", address)
        .param("score", result.score())
        .param("level", result.level().name())
        .param("levelRank", (short) result.level().rank())
        .param("onTime", result.onTimeCount())
        .param("late", result.lateCount())
        .param("defaults", result.defaultCount())
        .param("profileComplete", result.profileComplete())
        .param("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
        .update();
  }

  private static ReputationState state(ResultSet rs, int row) throws SQLException {
    return new ReputationState(
        rs.getString("address").trim(),
        rs.getInt("score"),
        ReputationLevel.valueOf(rs.getString("level")),
        rs.getInt("on_time_count"),
        rs.getInt("late_count"),
        rs.getInt("default_count"),
        rs.getBoolean("profile_complete"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Entry entry(ResultSet rs, int row) throws SQLException {
    BigDecimal loanId = rs.getBigDecimal("loan_id");
    String txHash = rs.getString("tx_hash");
    int daysLate = rs.getInt("days_late");
    boolean daysLateNull = rs.wasNull();
    int logIndex = rs.getInt("log_index");
    boolean logIndexNull = rs.wasNull();
    return new Entry(
        rs.getLong("id"),
        SignalType.valueOf(rs.getString("type")),
        loanId == null ? null : loanId.toBigIntegerExact().toString(),
        rs.getInt("delta"),
        rs.getInt("score_after"),
        daysLateNull ? null : daysLate,
        txHash == null ? null : txHash.trim(),
        logIndexNull ? null : logIndex,
        rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
  }
}
