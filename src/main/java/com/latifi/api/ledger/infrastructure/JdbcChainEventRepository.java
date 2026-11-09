package com.latifi.api.ledger.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
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
              last_block = excluded.last_block,
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
