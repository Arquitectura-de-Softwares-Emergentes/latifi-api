package com.latifi.api.ledger.infrastructure;

import com.latifi.api.config.ConditionalOnChainRpc;
import com.latifi.api.ledger.application.IndexerService;
import com.latifi.api.ledger.domain.CycleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the indexer on a fixed delay. The delay counts from the end of one cycle, so two cycles
 * never overlap, and a cycle that fails never stops the schedule.
 */
@Component
@ConditionalOnChainRpc
@ConditionalOnProperty(name = "latifi.indexer.enabled", havingValue = "true", matchIfMissing = true)
public class IndexerScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(IndexerScheduler.class);

  private final IndexerService indexer;

  public IndexerScheduler(IndexerService indexer) {
    this.indexer = indexer;
  }

  @Scheduled(fixedDelayString = "${latifi.indexer.poll-interval}")
  public void tick() {
    try {
      CycleResult result = indexer.runOnce();
      if (result.skipped()) {
        LOG.info("Indexer cycle skipped: {}", result.skippedReason());
      } else if (result.eventsInserted() > 0) {
        LOG.info(
            "Indexer mirrored {} new events up to block {}",
            result.eventsInserted(),
            result.lastBlock());
      }
    } catch (Exception e) {
      // Only the class name goes to the log: a transport message can quote the node URL.
      LOG.warn("Indexer cycle failed, the next one retries: {}", e.getClass().getSimpleName());
    }
  }
}
