package com.latifi.api.ledger.infrastructure;

import com.latifi.api.config.ChainProperties;
import com.latifi.api.config.IndexerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Says at startup whether the indexer runs. With no RPC URL the API still starts and the indexer
 * stays off, and this runner warns once so the operator notices. The log shows the host only.
 */
@Component
@ConditionalOnProperty(name = "latifi.indexer.enabled", havingValue = "true", matchIfMissing = true)
public class IndexerStartupReport implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(IndexerStartupReport.class);

  private final ChainProperties chain;
  private final IndexerProperties indexer;

  public IndexerStartupReport(ChainProperties chain, IndexerProperties indexer) {
    this.chain = chain;
    this.indexer = indexer;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!chain.hasRpcUrl()) {
      LOG.warn("The indexer is enabled but latifi.chain.rpc-url is empty, so it stays off");
      return;
    }
    LOG.info(
        "The indexer starts against node host {}, every {}, from block {}",
        RpcUrls.redact(chain.rpcUrl()),
        indexer.pollInterval(),
        indexer.startBlock());
  }
}
