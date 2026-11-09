package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.ledger.infrastructure.IndexerStartupReport;
import com.latifi.api.ledger.infrastructure.RpcUrls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.web3j.protocol.Web3j;

@ExtendWith(OutputCaptureExtension.class)
class ChainConfigTest {

  private static final String SECRET_URL = "https://node.example.org/v2/SECRETKEY";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ChainConfig.class, IndexerStartupReport.class)
          .withPropertyValues(
              "latifi.chain.id=80002",
              "latifi.chain.loan-book-address=0xCCB8AD15A4954138C987A77924CEAB468A843266",
              "latifi.indexer.enabled=true",
              "latifi.indexer.poll-interval=PT5S",
              "latifi.indexer.batch-size=500",
              "latifi.indexer.reorg-window=12",
              "latifi.indexer.start-block=51675536");

  @Test
  void withoutAnRpcUrlThereIsNoWeb3jBeanAndTheContextStarts() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(Web3j.class);
        });
  }

  @Test
  void aBlankRpcUrlCountsAsNoUrl() {
    runner
        .withPropertyValues("latifi.chain.rpc-url=   ")
        .run(context -> assertThat(context).doesNotHaveBean(Web3j.class));
  }

  @Test
  void anRpcUrlCreatesTheWeb3jBean() {
    runner
        .withPropertyValues("latifi.chain.rpc-url=" + SECRET_URL)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(Web3j.class);
            });
  }

  @Test
  void theAddressIsKeptInLowercase() {
    runner.run(
        context ->
            assertThat(context.getBean(ChainProperties.class).loanBookAddress())
                .isEqualTo("0xccb8ad15a4954138c987a77924ceab468a843266"));
  }

  @Test
  void theStartupReportWarnsOnceWhenThereIsNoUrl(CapturedOutput output) {
    runner.run(
        context -> {
          context.getBean(IndexerStartupReport.class).run(null);
          assertThat(output.getOut()).contains("latifi.chain.rpc-url is empty, so it stays off");
        });
  }

  @Test
  void theStartupReportNamesTheHostAndNeverTheKey(CapturedOutput output) {
    runner
        .withPropertyValues("latifi.chain.rpc-url=" + SECRET_URL)
        .run(
            context -> {
              context.getBean(IndexerStartupReport.class).run(null);
              assertThat(output.getOut()).contains("node.example.org");
              assertThat(output.getOut()).doesNotContain("SECRETKEY");
              assertThat(context.getBean(ChainProperties.class).toString())
                  .doesNotContain("SECRETKEY");
            });
    assertThat(output.getAll()).doesNotContain("SECRETKEY");
  }

  @Test
  void redactKeepsOnlyTheHost() {
    assertThat(RpcUrls.redact(SECRET_URL)).isEqualTo("node.example.org");
    assertThat(RpcUrls.redact("http://127.0.0.1:8545/path?key=SECRETKEY")).isEqualTo("127.0.0.1");
    assertThat(RpcUrls.redact("not a url at all")).isEqualTo("invalid-url");
    assertThat(RpcUrls.redact("just-text")).isEqualTo("invalid-url");
    assertThat(RpcUrls.redact("")).isEqualTo("invalid-url");
    assertThat(RpcUrls.redact(null)).isEqualTo("invalid-url");
  }

  @Test
  void aBatchSizeOfZeroStopsTheContext() {
    runner
        .withPropertyValues("latifi.indexer.batch-size=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aNegativeStartBlockStopsTheContext() {
    runner
        .withPropertyValues("latifi.indexer.start-block=-1")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aReorgWindowOfZeroStopsTheContext() {
    runner
        .withPropertyValues("latifi.indexer.reorg-window=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
