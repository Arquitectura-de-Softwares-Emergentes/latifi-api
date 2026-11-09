package com.latifi.api.ledger.application;

import com.latifi.api.config.ChainProperties;
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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mirrors the LoanBook logs into the database. Each cycle reads the chain from the checkpoint,
 * minus the re-read window, up to the newest block, in batches.
 *
 * <p>A batch is one transaction, and it runs in this order: take the advisory lock, read the
 * checkpoint, fetch the logs and the blocks that carry them, repair the window, insert the new
 * rows, move the checkpoint, tell the listeners. The node calls happen inside the transaction so
 * that the lock covers the whole read, compute and write sequence. One batch holds a pool
 * connection for the seconds it takes, which the poll cadence tolerates.
 *
 * <p>A reorg deeper than the re-read window is not repaired. The window size is a locked design
 * decision (D-07). An operator can lower the checkpoint by hand, and the next cycle then repairs
 * everything from there.
 */
@Service
@ConditionalOnChainRpc
public class IndexerService {

  /**
   * The key of the advisory lock that guards the checkpoint. The batch transaction takes it with
   * {@code pg_try_advisory_xact_lock}, which returns at once and frees itself at commit or
   * rollback. Any fixed long works. This one spells nothing, it only has to stay the same in every
   * instance of the API and differ from other locks of the database.
   */
  public static final long LOCK_KEY = 7_040_608_100_001L;

  private static final Logger LOG = LoggerFactory.getLogger(IndexerService.class);

  private final ChainLogSource source;
  private final ChainEventRepository repository;
  private final LoanBookEvents events;
  private final List<ChainEventListener> listeners;
  private final IndexerProperties properties;
  private final ChainProperties chain;
  private final TransactionTemplate transactions;

  /** Set once the node reported the configured chain id. A mismatch is checked again. */
  private volatile boolean chainVerified;

  public IndexerService(
      ChainLogSource source,
      ChainEventRepository repository,
      LoanBookEvents events,
      List<ChainEventListener> listeners,
      IndexerProperties properties,
      ChainProperties chain,
      TransactionTemplate transactions) {
    this.source = source;
    this.repository = repository;
    this.events = events;
    this.listeners = List.copyOf(listeners);
    this.properties = properties;
    this.chain = chain;
    this.transactions = transactions;
  }

  /**
   * Runs one cycle. A node failure throws and leaves the checkpoint where the last committed batch
   * put it. A cycle that finds a reason to stop returns a result with {@code skippedReason}, and
   * the batches it committed before that stay.
   */
  public CycleResult runOnce() {
    if (!chainVerified) {
      long nodeChainId = source.chainId();
      if (nodeChainId != chain.id()) {
        LOG.warn(
            "The node reports chain id {} but latifi.chain.id is {}, so the cycle is skipped",
            nodeChainId,
            chain.id());
        return new CycleResult(0, 0, 0, currentCheckpoint(), CycleResult.CHAIN_ID_MISMATCH);
      }
      chainVerified = true;
    }

    long latest = source.latestBlock();
    Totals totals = new Totals();
    Long cursor = null;
    while (true) {
      Long start = cursor;
      BatchOutcome outcome = transactions.execute(status -> applyBatch(start, latest));
      if (outcome == null) {
        throw new IllegalStateException("a batch transaction always returns an outcome");
      }
      if (outcome.skippedReason() != null) {
        long lastBlock = totals.batches > 0 ? totals.last : outcome.checkpoint();
        return new CycleResult(
            totals.scanned, totals.inserted, totals.removed, lastBlock, outcome.skippedReason());
      }
      totals.add(outcome);
      if (outcome.to() >= latest) {
        return new CycleResult(totals.scanned, totals.inserted, totals.removed, totals.last, null);
      }
      cursor = outcome.to() + 1;
    }
  }

  /**
   * One batch inside its transaction. {@code cursor} is {@code null} for the first batch of a
   * cycle, which starts at the checkpoint minus the window, and the next block for the others.
   */
  private BatchOutcome applyBatch(Long cursor, long latest) {
    long startBlock = properties.startBlock();
    if (!repository.tryAdvisoryLock(LOCK_KEY)) {
      return BatchOutcome.skipped(CycleResult.LOCK_HELD, currentCheckpoint());
    }
    // The checkpoint is read after the lock, so a batch that waited for another instance sees what
    // that instance wrote.
    long checkpoint = repository.checkpoint().orElse(startBlock - 1);
    if (latest < checkpoint) {
      return BatchOutcome.skipped(CycleResult.NODE_BEHIND, checkpoint);
    }
    // The checkpoint block is the first of the re-read window, so a window of 12 starts 11 back.
    long from =
        cursor != null
            ? cursor
            : Math.max(startBlock, checkpoint - (properties.reorgWindow() - 1L));
    if (from > latest) {
      return BatchOutcome.skipped(CycleResult.UP_TO_DATE, checkpoint);
    }
    long to = Math.min(from + source.suggestedBatchSize(properties.batchSize()) - 1L, latest);

    Map<Long, BlockInfo> blocks = new HashMap<>();
    List<ChainEvent> decoded = new ArrayList<>();
    for (RawLog log : source.logs(from, to)) {
      BlockInfo block = blocks.computeIfAbsent(log.blockNumber(), source::block);
      events.decode(log, block).ifPresent(decoded::add);
    }

    // The delete comes first: a re-included log shares its unique key with the stale row.
    List<ChainEvent> removed = repository.reconcileWindow(from, to, decoded);
    List<ChainEvent> fresh = repository.insertNew(decoded);
    repository.saveCheckpoint(to);
    if (!fresh.isEmpty() || !removed.isEmpty()) {
      Set<BigInteger> touched = new HashSet<>();
      fresh.forEach(event -> touched.add(event.loanId()));
      removed.forEach(event -> touched.add(event.loanId()));
      BatchDelta delta = new BatchDelta(touched);
      listeners.forEach(listener -> listener.onBatchApplied(delta));
    }
    LOG.debug(
        "Indexed blocks {} to {}: {} new events, {} removed",
        from,
        to,
        fresh.size(),
        removed.size());
    return new BatchOutcome(null, to - from + 1, fresh.size(), removed.size(), to, to);
  }

  /** A read outside any batch, for the reports of a skipped cycle. */
  private long currentCheckpoint() {
    return repository.checkpoint().orElse(properties.startBlock() - 1);
  }

  /**
   * What one batch did.
   *
   * @param skippedReason why the batch stopped before it did anything, or {@code null}
   * @param to the last block of the batch, meaningful when it ran
   * @param checkpoint the checkpoint after the batch, or the one found when it stopped
   */
  private record BatchOutcome(
      String skippedReason, long scanned, int inserted, int removed, long to, long checkpoint) {

    static BatchOutcome skipped(String reason, long checkpoint) {
      return new BatchOutcome(reason, 0, 0, 0, -1, checkpoint);
    }
  }

  /** Running sums of the batches that a cycle committed. */
  private static final class Totals {
    long scanned;
    int inserted;
    int removed;
    long last;
    int batches;

    void add(BatchOutcome outcome) {
      scanned += outcome.scanned();
      inserted += outcome.inserted();
      removed += outcome.removed();
      last = outcome.to();
      batches++;
    }
  }
}
