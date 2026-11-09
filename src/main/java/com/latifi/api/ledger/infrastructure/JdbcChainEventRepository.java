package com.latifi.api.ledger.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventRepository;
import com.latifi.api.ledger.domain.ChainEventType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores mirrored logs in {@code chain_event} and the cursor in {@code indexer_checkpoint}. */
@Repository
public class JdbcChainEventRepository implements ChainEventRepository {

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final Clock clock;

  public JdbcChainEventRepository(JdbcClient jdbc, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  /**
   * One statement per event. {@code returning} yields a row only when the event was new, which is
   * how the caller learns what to hand to the listeners.
   */
  @Override
  public List<ChainEvent> insertNew(List<ChainEvent> events) {
    List<ChainEvent> inserted = new ArrayList<>();
    for (ChainEvent event : events) {
      boolean isNew =
          jdbc.sql(
                  """
                  insert into chain_event
                    (tx_hash, log_index, block_number, block_hash, block_timestamp,
                     event_type, loan_id, payload)
                  values
                    (:txHash, :logIndex, :blockNumber, :blockHash, :blockTimestamp,
                     :type, :loanId, cast(:payload as jsonb))
                  ON CONFLICT (tx_hash, log_index) DO NOTHING
                  returning id
                  """)
              .param("txHash", event.txHash())
              .param("logIndex", event.logIndex())
              .param("blockNumber", event.blockNumber())
              .param("blockHash", event.blockHash())
              .param(
                  "blockTimestamp",
                  OffsetDateTime.ofInstant(event.blockTimestamp(), ZoneOffset.UTC))
              .param("type", event.type().name())
              .param("loanId", new BigDecimal(event.loanId()))
              .param("payload", payloadJson(event))
              .query(Long.class)
              .optional()
              .isPresent();
      if (isNew) {
        inserted.add(event);
      }
    }
    return inserted;
  }

  /**
   * Loads the stored rows of the range and of the transactions in {@code fresh}, then decides in
   * Java which rows the chain no longer holds. The decision stays out of SQL on purpose: a three
   * part key compared against a list is easier to read and to test here.
   */
  @Override
  public List<ChainEvent> reconcileWindow(long fromBlock, long toBlock, List<ChainEvent> fresh) {
    Set<String> freshTriples = new HashSet<>();
    Map<String, String> freshBlockHashByKey = new HashMap<>();
    Set<String> freshTxHashes = new HashSet<>();
    for (ChainEvent event : fresh) {
      freshTriples.add(triple(event.txHash(), event.logIndex(), event.blockHash()));
      freshBlockHashByKey.put(key(event.txHash(), event.logIndex()), event.blockHash());
      freshTxHashes.add(event.txHash());
    }

    List<StoredEvent> doomed = new ArrayList<>();
    for (StoredEvent stored :
        jdbc.sql(SELECT_EVENT + " where block_number between :from and :to")
            .param("from", fromBlock)
            .param("to", toBlock)
            .query(this::storedEvent)
            .list()) {
      ChainEvent event = stored.event();
      if (!freshTriples.contains(triple(event.txHash(), event.logIndex(), event.blockHash()))) {
        doomed.add(stored);
      }
    }
    if (!freshTxHashes.isEmpty()) {
      for (StoredEvent stored :
          jdbc.sql(
                  SELECT_EVENT
                      + " where tx_hash in (:txHashes) and block_number not between :from and :to")
              .param("txHashes", List.copyOf(freshTxHashes))
              .param("from", fromBlock)
              .param("to", toBlock)
              .query(this::storedEvent)
              .list()) {
        ChainEvent event = stored.event();
        String freshHash = freshBlockHashByKey.get(key(event.txHash(), event.logIndex()));
        if (freshHash != null && !freshHash.equals(event.blockHash())) {
          doomed.add(stored);
        }
      }
    }
    if (doomed.isEmpty()) {
      return List.of();
    }
    jdbc.sql("delete from chain_event where id in (:ids)")
        .param("ids", doomed.stream().map(StoredEvent::id).toList())
        .update();
    return doomed.stream().map(StoredEvent::event).toList();
  }

  private static final String SELECT_EVENT =
      """
      select id, tx_hash, log_index, block_number, block_hash, block_timestamp,
             event_type, loan_id, payload
      from chain_event
      """;

  private record StoredEvent(long id, ChainEvent event) {}

  private StoredEvent storedEvent(ResultSet rs, int row) throws SQLException {
    Map<String, String> payload;
    try {
      payload = json.readValue(rs.getString("payload"), new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a stored payload is always a map of text values", e);
    }
    ChainEvent event =
        new ChainEvent(
            rs.getString("tx_hash").trim(),
            rs.getInt("log_index"),
            rs.getLong("block_number"),
            rs.getString("block_hash").trim(),
            rs.getObject("block_timestamp", OffsetDateTime.class).toInstant(),
            ChainEventType.valueOf(rs.getString("event_type")),
            rs.getBigDecimal("loan_id").toBigIntegerExact(),
            payload);
    return new StoredEvent(rs.getLong("id"), event);
  }

  private static String key(String txHash, int logIndex) {
    return txHash + ":" + logIndex;
  }

  private static String triple(String txHash, int logIndex, String blockHash) {
    return key(txHash, logIndex) + ":" + blockHash;
  }

  /** Runs pg_try_advisory_xact_lock on the connection of the running batch transaction. */
  @Override
  public boolean tryAdvisoryLock(long key) {
    return Boolean.TRUE.equals(
        jdbc.sql("select pg_try_advisory_xact_lock(:key)")
            .param("key", key)
            .query(Boolean.class)
            .single());
  }

  @Override
  public OptionalLong checkpoint() {
    return jdbc.sql("select last_block from indexer_checkpoint where id = 1")
        .query(Long.class)
        .optional()
        .map(OptionalLong::of)
        .orElseGet(OptionalLong::empty);
  }

  @Override
  public void saveCheckpoint(long block) {
    jdbc.sql(
            """
            insert into indexer_checkpoint (id, last_block, updated_at)
            values (1, :block, :now)
            on conflict (id) do update set
              last_block = greatest(indexer_checkpoint.last_block, excluded.last_block),
              updated_at = excluded.updated_at
            """)
        .param("block", block)
        .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        .update();
  }

  private String payloadJson(ChainEvent event) {
    try {
      return json.writeValueAsString(event.payload());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a payload of text values always serializes", e);
    }
  }
}
