package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.config.ChainProperties;
import com.latifi.api.config.IndexerProperties;
import com.latifi.api.ledger.application.IndexerService;
import com.latifi.api.ledger.domain.ChainEventListener;
import com.latifi.api.ledger.domain.ChainEventRepository;
import com.latifi.api.ledger.domain.CycleResult;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import com.latifi.api.ledger.infrastructure.RpcUrls;
import com.latifi.api.ledger.infrastructure.Web3jLogSource;
import com.latifi.api.support.ContractAssertions;
import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.LatifiIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * Optional smoke test against the real Polygon Amoy network. A normal build skips it: it runs only
 * when {@code LATIFI_LIVE} is {@code true} and {@code LATIFI_RPC_URL} holds a node URL. It reads
 * logs and nothing else. It holds no key, signs nothing and sends no transaction.
 *
 * <p>The test builds its own node client from the environment, because the Spring properties of a
 * test point at the simulated node. It indexes a block range into the test database, then reads
 * every mirrored loan and every borrower reputation back through the HTTP API. A range with no
 * LoanBook event ends as skipped, never as passed. The runbook is {@code docs/LIVE-INTEGRATION.md}.
 */
@LatifiIntegrationTest
@EnabledIfEnvironmentVariable(named = "LATIFI_LIVE", matches = "true")
@EnabledIfEnvironmentVariable(named = "LATIFI_RPC_URL", matches = ".+")
@TestPropertySource(properties = "latifi.test.context=amoy-live")
class AmoyLiveTest {

  private static final Logger LOG = LoggerFactory.getLogger(AmoyLiveTest.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long AMOY_CHAIN_ID = 80_002L;
  private static final int MAX_CYCLES = 200;
  private static final long DEFAULT_LOOKBACK = 2_000L;
  private static final int BATCH_SIZE = 500;

  @Autowired private DatabaseReset databaseReset;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ChainEventRepository repository;
  @Autowired private List<ChainEventListener> listeners;
  @Autowired private TransactionTemplate transactions;
  @Autowired private IndexerProperties baseIndexer;

  @Value("${local.server.port}")
  private int port;

  @Test
  void indexesTheRangeAndServesEveryMirroredLoan() throws IOException {
    String rpcUrl = System.getenv("LATIFI_RPC_URL");
    LoanBookFacts facts = loanBookFacts();
    ChainProperties chain = new ChainProperties(AMOY_CHAIN_ID, rpcUrl, facts.address());
    Web3j web3j = Web3j.build(new HttpService(rpcUrl));
    try {
      Web3jLogSource source = new Web3jLogSource(web3j, chain.loanBookAddress());
      assertThat(source.chainId())
          .as("the node behind %s must be Polygon Amoy", RpcUrls.redact(rpcUrl))
          .isEqualTo(AMOY_CHAIN_ID);

      long head = source.latestBlock();
      long startBlock = startBlock(head, facts.deploymentBlock());
      databaseReset.run();

      IndexerProperties properties =
          new IndexerProperties(
              baseIndexer.enabled(),
              baseIndexer.pollInterval(),
              BATCH_SIZE,
              baseIndexer.reorgWindow(),
              startBlock);
      IndexerService indexer =
          new IndexerService(
              source,
              repository,
              new LoanBookEvents(chain.loanBookAddress()),
              List.copyOf(listeners),
              properties,
              chain,
              transactions);

      CycleResult last = null;
      for (int cycle = 0; cycle < MAX_CYCLES; cycle++) {
        last = indexer.runOnce();
        if (last.skipped() || last.lastBlock() >= head) {
          break;
        }
      }
      assertThat(last).isNotNull();
      Assumptions.assumeFalse(
          CycleResult.UP_TO_DATE.equals(last.skippedReason()),
          "The start block " + startBlock + " is above the head " + head);
      assertThat(last.skippedReason()).as("the indexer must not skip a live cycle").isNull();
      assertThat(last.lastBlock())
          .as("the checkpoint must reach the head within %d cycles", MAX_CYCLES)
          .isGreaterThanOrEqualTo(head);

      Integer events = jdbc.queryForObject("select count(*) from chain_event", Integer.class);
      String range = "blocks " + startBlock + " to " + head;
      Assumptions.assumeTrue(
          events != null && events > 0,
          "No LoanBook event in "
              + range
              + ". Emit one event or widen the range, see docs/LIVE-INTEGRATION.md.");

      List<BigDecimal> loanIds =
          jdbc.queryForList("select loan_id from loan order by loan_id", BigDecimal.class);
      Assumptions.assumeFalse(
          loanIds.isEmpty(),
          events
              + " events in "
              + range
              + " but no loan row, so the range misses every LoanRequested event."
              + " Start earlier, see docs/LIVE-INTEGRATION.md.");
      List<String> borrowers =
          jdbc
              .queryForList("select distinct borrower from loan order by borrower", String.class)
              .stream()
              .map(String::trim)
              .toList();

      ApiClient api = new ApiClient(port);
      api.withBearer(TestWallet.create().login(api));
      for (BigDecimal loanId : loanIds) {
        read(api, "/api/v1/loans/" + loanId.toBigInteger());
      }
      for (String borrower : borrowers) {
        read(api, "/api/v1/reputation/" + borrower);
      }
      LOG.info(
          "Amoy live smoke: {} events, {} loans and {} borrowers read back, {}",
          events,
          loanIds.size(),
          borrowers.size(),
          range);
    } finally {
      web3j.shutdown();
    }
  }

  /** Reads one path as the signed-in test wallet and checks status 200 and the contract. */
  private static void read(ApiClient api, String path) {
    api.get(path);
    assertThat(api.status()).as("GET %s answered %s", path, api.body()).isEqualTo(200);
    ContractAssertions.assertResponseConforms(
        Request.Method.GET, path, api.status(), api.header("content-type"), api.body());
  }

  /**
   * The start block: the environment value, else the head minus the look back, never before the
   * deployment.
   */
  private static long startBlock(long head, long deploymentBlock) {
    String configured = System.getenv("LATIFI_INDEXER_START_BLOCK");
    if (configured != null && !configured.isBlank()) {
      return parse("LATIFI_INDEXER_START_BLOCK", configured);
    }
    String lookback = System.getenv("LATIFI_LIVE_LOOKBACK");
    long window =
        lookback == null || lookback.isBlank()
            ? DEFAULT_LOOKBACK
            : parse("LATIFI_LIVE_LOOKBACK", lookback);
    return Math.max(deploymentBlock, head - window);
  }

  private static long parse(String name, String value) {
    try {
      long parsed = Long.parseLong(value.trim());
      if (parsed < 0) {
        throw new IllegalArgumentException(name + " must not be negative");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be a whole number of blocks", e);
    }
  }

  private record LoanBookFacts(String address, long deploymentBlock) {}

  /** The Amoy release facts. {@code LATIFI_LOANBOOK_ADDRESS} overrides the address. */
  private static LoanBookFacts loanBookFacts() throws IOException {
    JsonNode loanBook;
    try (InputStream in =
        AmoyLiveTest.class.getClassLoader().getResourceAsStream("deployments/amoy.json")) {
      assertThat(in).as("the fixture file deployments/amoy.json").isNotNull();
      loanBook = JSON.readTree(in).get("contracts").get("LoanBook");
    }
    String override = System.getenv("LATIFI_LOANBOOK_ADDRESS");
    String address =
        override != null && !override.isBlank()
            ? override.trim()
            : loanBook.get("address").asText();
    return new LoanBookFacts(address, Long.decode(loanBook.get("blockNumber").asText()));
  }
}
