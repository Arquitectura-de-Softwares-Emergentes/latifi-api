package com.latifi.api.ledger.domain;

import java.util.List;

/**
 * Read only view of the chain. The implementation talks JSON-RPC and never signs or sends. Every
 * method throws {@link ChainRpcException} when the node fails.
 */
public interface ChainLogSource {

  /** The newest block number the node knows. */
  long latestBlock();

  /** The chain id the node reports. */
  long chainId();

  /**
   * The LoanBook logs of the four indexed events in {@code fromBlock..toBlock}, both inclusive. A
   * range that the node rejects is split until it is accepted, down to one block.
   */
  List<RawLog> logs(long fromBlock, long toBlock);

  /** Hash and timestamp of one block. */
  BlockInfo block(long number);

  /**
   * The batch size the indexer should use now. A provider that rejected a wide range makes the
   * source remember a smaller size that it accepted, so the next cycles do not hit the same limit.
   *
   * @param configured the batch size from the configuration
   * @return the smaller of the configured size and the size that the node accepted
   */
  int suggestedBatchSize(int configured);
}
