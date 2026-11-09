package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.config.ChainProperties;
import com.latifi.api.config.IndexerProperties;
import com.latifi.api.ledger.application.IndexerService;
import com.latifi.api.ledger.domain.ChainEventListener;
import com.latifi.api.ledger.domain.ChainEventRepository;
import com.latifi.api.ledger.domain.CycleResult;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import com.latifi.api.ledger.infrastructure.Web3jLogSource;
import com.latifi.api.support.ChainFixtures;
import com.latifi.api.support.FakeChainNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.Web3j;

/**
 * Replays the planned live cycle of the contracts repository on the simulated node. The chain id,
 * the LoanBook address and the deployment block come from {@code deployments/amoy.json}, a subset
 * of the file in the contracts repository, so a new deployment shows up here as a failing test and
 * not as a silent drift.
 *
 * <p>The numbers are those of the cycle checklist: a loan of 100000000 units at 1000 basis points
 * for one day in two installments has interest 27397 and total due 100027397, and the borrower pays
 * 50013698 and then 50013699.
 */
public class AmoyFixtureSteps {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long CYCLE_BLOCKS = 40;
  private static final long DAY = 86_400L;

  private static final BigInteger PRINCIPAL = BigInteger.valueOf(100_000_000L);
  private static final BigInteger FIRST_INSTALLMENT = BigInteger.valueOf(50_013_698L);
  private static final BigInteger SECOND_INSTALLMENT = BigInteger.valueOf(50_013_699L);

  private final FakeChainNode node;
  private final ApplicationContext context;

  private long chainId;
  private String loanBook;
  private long deploymentBlock;
  private CycleResult lastResult;

  public AmoyFixtureSteps(FakeChainNode node, ApplicationContext context) {
    this.node = node;
    this.context = context;
  }

  @Given("a simulated node copies the Amoy chain id and deployment block of the fixture file")
  public void nodeCopiesAmoy() throws IOException {
    JsonNode fixture;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("deployments/amoy.json")) {
      assertThat(in).as("the fixture file deployments/amoy.json").isNotNull();
      fixture = JSON.readTree(in);
    }
    chainId = fixture.get("chainId").asLong();
    loanBook = fixture.get("contracts").get("LoanBook").get("address").asText();
    deploymentBlock =
        Long.decode(fixture.get("contracts").get("LoanBook").get("blockNumber").asText());
    assertThat(chainId).isEqualTo(80002L);
    assertThat(deploymentBlock).isEqualTo(51_675_536L);

    node.chainId(chainId);
    node.mineTo(deploymentBlock + CYCLE_BLOCKS);
  }

  /**
   * Request, fund and two installments, each in its own block after the deployment block. The
   * repayments land before their due dates: the funding time is the timestamp of the funding block,
   * and the due dates run half a day and one day after it.
   */
  @Given("the planned Amoy cycle is emitted after the deployment block")
  public void cycleEmitted() {
    long requestBlock = deploymentBlock + 5;
    long fundBlock = deploymentBlock + 10;
    long firstRepayBlock = deploymentBlock + 20;
    long secondRepayBlock = deploymentBlock + 30;

    long fundedAt = node.timestampOf(fundBlock);
    long dueDate = fundedAt + DAY;
    node.timestamp(firstRepayBlock, fundedAt + 7_200L);
    node.timestamp(secondRepayBlock, fundedAt + 50_000L);

    BigInteger loan = BigInteger.ONE;
    node.emit(
        requestBlock,
        ChainFixtures.loanRequested(
                ChainFixtures.txHash(9_001), 0, loan, ChainFixtures.BORROWER, PRINCIPAL, 1000, 1, 2)
            .withAddress(loanBook));
    node.emit(
        fundBlock,
        ChainFixtures.loanFunded(
                ChainFixtures.txHash(9_002),
                0,
                loan,
                ChainFixtures.LENDER,
                ChainFixtures.BORROWER,
                PRINCIPAL,
                fundedAt,
                dueDate)
            .withAddress(loanBook));
    node.emit(
        firstRepayBlock,
        ChainFixtures.loanRepaid(ChainFixtures.txHash(9_003), 0, loan, FIRST_INSTALLMENT, true)
            .withAddress(loanBook));
    node.emit(
        secondRepayBlock,
        ChainFixtures.loanRepaid(ChainFixtures.txHash(9_004), 0, loan, SECOND_INSTALLMENT, true)
            .withAddress(loanBook));
  }

  /** A log with the shape of LoanRequested from an address that is not the LoanBook. */
  @Given("a log of another contract is emitted in the blocks of the cycle")
  public void foreignLogEmitted() {
    String other = "0x00000000000000000000000000000000000000aa";
    assertThat(other).isNotEqualToIgnoringCase(loanBook);
    node.emit(
        deploymentBlock + 5,
        ChainFixtures.loanRequested(
                ChainFixtures.txHash(9_005),
                1,
                BigInteger.TEN,
                ChainFixtures.BORROWER,
                BigInteger.valueOf(1),
                500,
                1,
                1)
            .withAddress(other));
    node.emit(
        deploymentBlock + 20,
        ChainFixtures.loanRequested(
                ChainFixtures.txHash(9_006),
                1,
                BigInteger.valueOf(11),
                ChainFixtures.BORROWER,
                BigInteger.valueOf(1),
                500,
                1,
                1)
            .withAddress(other));
  }

  /**
   * A fresh service built from the collaborator beans, with the chain id and the address of the
   * fixture, the deployment block as start block and a batch of 10 blocks. It repeats the cycle
   * until the checkpoint reaches the head, because a start block far behind the head is read in
   * batches.
   */
  @When("an indexer that starts at the deployment block runs until the head")
  public void indexerRuns() {
    IndexerProperties base = context.getBean(IndexerProperties.class);
    ChainProperties baseChain = context.getBean(ChainProperties.class);
    IndexerProperties properties =
        new IndexerProperties(
            base.enabled(), base.pollInterval(), 10, base.reorgWindow(), deploymentBlock);
    ChainProperties chain = new ChainProperties(chainId, baseChain.rpcUrl(), loanBook);
    IndexerService indexer =
        new IndexerService(
            new Web3jLogSource(context.getBean(Web3j.class), chain.loanBookAddress()),
            context.getBean(ChainEventRepository.class),
            new LoanBookEvents(chain.loanBookAddress()),
            List.copyOf(context.getBeansOfType(ChainEventListener.class).values()),
            properties,
            chain,
            context.getBean(TransactionTemplate.class));
    lastResult = indexer.runOnce();
  }

  @Then("the indexer ended with no skip reason")
  public void noSkipReason() {
    assertThat(lastResult).isNotNull();
    assertThat(lastResult.skippedReason()).isNull();
    assertThat(lastResult.lastBlock()).isEqualTo(deploymentBlock + CYCLE_BLOCKS);
  }
}
