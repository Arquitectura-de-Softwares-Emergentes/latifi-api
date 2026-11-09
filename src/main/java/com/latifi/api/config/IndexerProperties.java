package com.latifi.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Indexer settings bound to {@code latifi.indexer}. A value out of range throws at startup, which
 * stops the application context.
 *
 * @param enabled whether the scheduled cycle runs
 * @param pollInterval pause between the end of one cycle and the start of the next
 * @param batchSize blocks per {@code eth_getLogs} call
 * @param reorgWindow blocks every cycle reads again, counting the checkpoint block
 * @param startBlock first block to scan when no checkpoint exists
 */
@ConfigurationProperties(prefix = "latifi.indexer")
public record IndexerProperties(
    boolean enabled, Duration pollInterval, int batchSize, int reorgWindow, long startBlock) {

  public IndexerProperties {
    if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("latifi.indexer.poll-interval must be positive");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("latifi.indexer.batch-size must be at least 1");
    }
    if (reorgWindow < 1) {
      throw new IllegalArgumentException("latifi.indexer.reorg-window must be at least 1");
    }
    if (startBlock < 0) {
      throw new IllegalArgumentException("latifi.indexer.start-block must be at least 0");
    }
  }
}
