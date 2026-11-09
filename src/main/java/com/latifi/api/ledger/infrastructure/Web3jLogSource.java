package com.latifi.api.ledger.infrastructure;

import com.latifi.api.ledger.domain.BlockInfo;
import com.latifi.api.ledger.domain.ChainLogSource;
import com.latifi.api.ledger.domain.ChainRpcErrorException;
import com.latifi.api.ledger.domain.ChainRpcException;
import com.latifi.api.ledger.domain.RawLog;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;

/**
 * Reads the chain through web3j over JSON-RPC. Messages of {@link ChainRpcException} name the
 * failed call and the error code, never the node URL.
 */
public class Web3jLogSource implements ChainLogSource {

  private final Web3j web3j;
  private final String loanBookAddress;

  /**
   * The largest range that a provider is known to accept, learned after a rejection. It starts
   * unbounded and resets when the process restarts.
   */
  private final AtomicInteger acceptedSize = new AtomicInteger(Integer.MAX_VALUE);

  public Web3jLogSource(Web3j web3j, String loanBookAddress) {
    this.web3j = web3j;
    this.loanBookAddress = loanBookAddress.toLowerCase(Locale.ROOT);
  }

  @Override
  public long latestBlock() {
    BigInteger number =
        call("eth_blockNumber", () -> web3j.ethBlockNumber().send()).getBlockNumber();
    return number.longValueExact();
  }

  @Override
  public long chainId() {
    return call("eth_chainId", () -> web3j.ethChainId().send()).getChainId().longValueExact();
  }

  @Override
  public int suggestedBatchSize(int configured) {
    return Math.min(configured, acceptedSize.get());
  }

  /**
   * Asks for the whole range. When the node answers with a JSON-RPC error and the range spans more
   * than one block, the range is halved and both halves are asked again. The first range that the
   * node then accepts becomes the size for the rest of the call and for later calls, so a provider
   * limit costs a handful of rejected calls once and none afterwards. One block that the node still
   * rejects ends the call with the exception. A transport failure is never retried here, because
   * halving cannot cure a node that is down.
   */
  @Override
  public List<RawLog> logs(long fromBlock, long toBlock) {
    List<RawLog> result = new ArrayList<>();
    Deque<long[]> pending = new ArrayDeque<>();
    pending.push(new long[] {fromBlock, toBlock});
    boolean learning = false;
    while (!pending.isEmpty()) {
      long[] range = pending.pop();
      long size = range[1] - range[0] + 1;
      long known = acceptedSize.get();
      if (size > known) {
        splitInto(pending, range, known);
        continue;
      }
      try {
        result.addAll(fetchLogs(range[0], range[1]));
      } catch (ChainRpcErrorException e) {
        if (size == 1) {
          throw e;
        }
        learning = true;
        splitInto(pending, range, (size + 1) / 2);
        continue;
      }
      if (learning) {
        acceptedSize.accumulateAndGet((int) size, Math::min);
        learning = false;
      }
    }
    return result;
  }

  /** Pushes the pieces so that the lowest block range is popped first. */
  private static void splitInto(Deque<long[]> pending, long[] range, long pieceSize) {
    List<long[]> pieces = new ArrayList<>();
    for (long start = range[0]; start <= range[1]; start += pieceSize) {
      pieces.add(new long[] {start, Math.min(start + pieceSize - 1, range[1])});
    }
    for (int i = pieces.size() - 1; i >= 0; i--) {
      pending.push(pieces.get(i));
    }
  }

  private List<RawLog> fetchLogs(long fromBlock, long toBlock) {
    EthFilter filter =
        new EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(fromBlock)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(toBlock)),
                loanBookAddress)
            .addOptionalTopics(LoanBookEvents.ALL_TOPICS.toArray(String[]::new));
    EthLog response = call("eth_getLogs", () -> web3j.ethGetLogs(filter).send());
    List<RawLog> logs = new ArrayList<>();
    for (EthLog.LogResult<?> result : response.getLogs()) {
      if (!(result.get() instanceof Log log)) {
        throw new ChainRpcException("eth_getLogs returned an entry that is not a log");
      }
      logs.add(
          new RawLog(
              log.getAddress(),
              log.getTopics(),
              log.getData(),
              log.getTransactionHash(),
              log.getLogIndex().intValueExact(),
              log.getBlockNumber().longValueExact(),
              log.getBlockHash(),
              log.isRemoved()));
    }
    return logs;
  }

  @Override
  public BlockInfo block(long number) {
    EthBlock response =
        call(
            "eth_getBlockByNumber",
            () ->
                web3j
                    .ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(BigInteger.valueOf(number)), false)
                    .send());
    EthBlock.Block block = response.getBlock();
    if (block == null) {
      throw new ChainRpcException("eth_getBlockByNumber found no block " + number);
    }
    return new BlockInfo(
        number, block.getHash(), Instant.ofEpochSecond(block.getTimestamp().longValueExact()));
  }

  private <T extends Response<?>> T call(String method, RpcCall<T> action) {
    T response;
    try {
      response = action.run();
    } catch (IOException | RuntimeException e) {
      // The message of a transport failure can quote the URL, so only the class name goes up.
      throw new ChainRpcException(method + " failed: " + e.getClass().getSimpleName());
    }
    if (response.hasError()) {
      throw new ChainRpcErrorException(
          method
              + " returned error "
              + response.getError().getCode()
              + ": "
              + safeMessage(response),
          response.getError().getCode());
    }
    return response;
  }

  private static String safeMessage(Response<?> response) {
    String message = response.getError().getMessage();
    return message == null ? "no message" : message;
  }

  @FunctionalInterface
  private interface RpcCall<T> {
    T run() throws IOException;
  }
}
