package com.latifi.api.ledger.infrastructure;

import com.latifi.api.ledger.domain.BlockInfo;
import com.latifi.api.ledger.domain.ChainLogSource;
import com.latifi.api.ledger.domain.ChainRpcException;
import com.latifi.api.ledger.domain.RawLog;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
  public List<RawLog> logs(long fromBlock, long toBlock) {
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
      throw new ChainRpcException(
          method
              + " returned error "
              + response.getError().getCode()
              + ": "
              + safeMessage(response));
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
