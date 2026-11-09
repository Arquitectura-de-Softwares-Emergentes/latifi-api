package com.latifi.api.ledger.application;

import com.latifi.api.config.ConditionalOnChainRpc;
import com.latifi.api.config.IndexerProperties;
import com.latifi.api.ledger.domain.BatchDelta;
import com.latifi.api.ledger.domain.BlockInfo;
import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventListener;
import com.latifi.api.ledger.domain.ChainEventRepository;
import com.latifi.api.ledger.domain.ChainLogSource;
import com.latifi.api.ledger.domain.CycleResult;
import com.latifi.api.ledger.domain.RawLog;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mirrors the LoanBook logs into the database. Each cycle reads the chain from the checkpoint,
 * minus the re-read window, up to the newest block, in batches. A batch is one transaction: it
 * inserts the new rows, tells the listeners, and moves the checkpoint.
 */
@Service
@ConditionalOnChainRpc
public class IndexerService {

  private static final Logger LOG = LoggerFactory.getLogger(IndexerService.class);

  private final ChainLogSource source;
  private final ChainEventRepository repository;
  private final LoanBookEvents events;
  private final List<ChainEventListener> listeners;
  private final IndexerProperties properties;
  private final TransactionTemplate transactions;

  public IndexerService(
      ChainLogSource source,
      ChainEventRepository repository,
      LoanBookEvents events,
      List<ChainEventListener> listeners,
      IndexerProperties properties,
      TransactionTemplate transactions) {
    this.source = source;
    this.repository = repository;
    this.events = events;
    this.listeners = List.copyOf(listeners);
    this.properties = properties;
    this.transactions = transactions;
  }

  /**
   * Runs one cycle. A node failure throws and leaves the checkpoint where the last batch put it.
   */
  public CycleResult runOnce() {
    long startBlock = properties.startBlock();
    OptionalLong stored = repository.checkpoint();
    long checkpoint = stored.orElse(startBlock - 1);
    long latest = source.latestBlock();
    if (latest < checkpoint) {
      return new CycleResult(0, 0, 0, checkpoint, "node is behind the checkpoint");
    }
    // The checkpoint block is the first of the re-read window, so a window of 12 starts 11 back.
    long from = Math.max(startBlock, checkpoint - (properties.reorgWindow() - 1L));
    if (from > latest) {
      return new CycleResult(0, 0, 0, checkpoint, "start block is not mined yet");
    }
    long scanned = 0;
    int inserted = 0;
    int removed = 0;
    long last = checkpoint;
    while (from <= latest) {
      long to = Math.min(from + properties.batchSize() - 1L, latest);
      int[] changes = applyBatch(from, to);
      inserted += changes[0];
      removed += changes[1];
      scanned += to - from + 1;
      last = to;
      from = to + 1;
    }
    return new CycleResult(scanned, inserted, removed, last, null);
  }

  /**
   * One batch: fetch and decode, then repair the window, store the new rows and advance, all in a
   * single transaction.
   *
   * @return the number of inserted rows and the number of removed rows
   */
  private int[] applyBatch(long from, long to) {
    Map<Long, BlockInfo> blocks = new HashMap<>();
    List<ChainEvent> decoded = new ArrayList<>();
    for (RawLog log : source.logs(from, to)) {
      BlockInfo block = blocks.computeIfAbsent(log.blockNumber(), source::block);
      events.decode(log, block).ifPresent(decoded::add);
    }
    int[] changes =
        transactions.execute(
            status -> {
              // The delete comes first: a re-included log shares its unique key with the stale row.
              List<ChainEvent> removed = repository.reconcileWindow(from, to, decoded);
              List<ChainEvent> fresh = repository.insertNew(decoded);
              if (!fresh.isEmpty() || !removed.isEmpty()) {
                Set<BigInteger> touched = new HashSet<>();
                fresh.forEach(event -> touched.add(event.loanId()));
                removed.forEach(event -> touched.add(event.loanId()));
                BatchDelta delta = new BatchDelta(touched);
                listeners.forEach(listener -> listener.onBatchApplied(delta));
              }
              repository.saveCheckpoint(to);
              return new int[] {fresh.size(), removed.size()};
            });
    LOG.debug(
        "Indexed blocks {} to {}: {} new events, {} removed", from, to, changes[0], changes[1]);
    return changes;
  }
}
