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

  /** The LoanBook logs of the four indexed events in {@code fromBlock..toBlock}, both inclusive. */
  List<RawLog> logs(long fromBlock, long toBlock);

  /** Hash and timestamp of one block. */
  BlockInfo block(long number);
}
