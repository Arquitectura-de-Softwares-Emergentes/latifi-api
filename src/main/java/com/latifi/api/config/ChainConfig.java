package com.latifi.api.config;

import com.latifi.api.ledger.domain.ChainLogSource;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import com.latifi.api.ledger.infrastructure.Web3jLogSource;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * Chain access. The {@link Web3j} client exists only when an RPC URL is configured. It reads the
 * chain and nothing else: no credentials, no signing, no transactions.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ChainProperties.class, IndexerProperties.class})
public class ChainConfig {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  @Bean(destroyMethod = "shutdown")
  @ConditionalOnChainRpc
  Web3j web3j(ChainProperties chain) {
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(READ_TIMEOUT)
            .build();
    return Web3j.build(new HttpService(chain.rpcUrl(), client));
  }

  @Bean
  LoanBookEvents loanBookEvents(ChainProperties chain) {
    return new LoanBookEvents(chain.loanBookAddress());
  }

  @Bean
  @ConditionalOnChainRpc
  ChainLogSource chainLogSource(Web3j web3j, ChainProperties chain) {
    return new Web3jLogSource(web3j, chain.loanBookAddress());
  }
}
