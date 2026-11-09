package com.latifi.api.config;

import com.latifi.api.ledger.infrastructure.RpcUrls;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chain settings bound to {@code latifi.chain}.
 *
 * @param id the chain id the node must report
 * @param rpcUrl the JSON-RPC endpoint, empty when none is configured. It can carry an API key.
 * @param loanBookAddress the LoanBook contract, kept in lowercase
 */
@ConfigurationProperties(prefix = "latifi.chain")
public record ChainProperties(long id, String rpcUrl, String loanBookAddress) {

  public ChainProperties {
    rpcUrl = rpcUrl == null ? "" : rpcUrl.trim();
    loanBookAddress =
        loanBookAddress == null ? "" : loanBookAddress.trim().toLowerCase(Locale.ROOT);
  }

  public boolean hasRpcUrl() {
    return !rpcUrl.isBlank();
  }

  /** Prints the host of the URL only, so a logged record never leaks an API key. */
  @Override
  public String toString() {
    return "ChainProperties[id="
        + id
        + ", rpcHost="
        + (hasRpcUrl() ? RpcUrls.redact(rpcUrl) : "none")
        + ", loanBookAddress="
        + loanBookAddress
        + "]";
  }
}
