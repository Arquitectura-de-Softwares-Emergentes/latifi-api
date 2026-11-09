package com.latifi.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.ledger.domain.BlockInfo;
import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventType;
import com.latifi.api.ledger.domain.RawLog;
import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import com.latifi.api.support.ChainFixtures;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoanBookEventsTest {

  private static final BigInteger HUGE = BigInteger.TWO.pow(200);
  private static final Instant TIME = Instant.parse("2026-02-03T04:05:06Z");
  private static final String BLOCK_HASH = "0x" + "ab".repeat(32);
  private static final String TX = ChainFixtures.txHash(77);

  private final LoanBookEvents events = new LoanBookEvents(ChainFixtures.LOAN_BOOK);

  private static RawLog raw(ChainFixtures.Log log, boolean removed) {
    return new RawLog(
        log.address(),
        log.topics(),
        log.data(),
        log.txHash(),
        log.logIndex(),
        42,
        BLOCK_HASH,
        removed);
  }

  private Optional<ChainEvent> decode(ChainFixtures.Log log) {
    return events.decode(raw(log, false), new BlockInfo(42, BLOCK_HASH, TIME));
  }

  @Test
  void decodesLoanRequestedWithAHugeAmount() {
    ChainEvent event =
        decode(
                ChainFixtures.loanRequested(
                    TX, 5, HUGE, ChainFixtures.BORROWER, HUGE.add(BigInteger.ONE), 1200, 90, 3))
            .orElseThrow();

    assertThat(event.type()).isEqualTo(ChainEventType.LOAN_REQUESTED);
    assertThat(event.loanId()).isEqualTo(HUGE);
    assertThat(event.txHash()).isEqualTo(TX);
    assertThat(event.logIndex()).isEqualTo(5);
    assertThat(event.blockNumber()).isEqualTo(42);
    assertThat(event.blockHash()).isEqualTo(BLOCK_HASH);
    assertThat(event.blockTimestamp()).isEqualTo(TIME);
    assertThat(event.payload())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "borrower", ChainFixtures.BORROWER,
                "amount", HUGE.add(BigInteger.ONE).toString(),
                "interestRateBps", "1200",
                "termDays", "90",
                "installments", "3"));
  }

  @Test
  void decodesLoanFunded() {
    ChainEvent event =
        decode(
                ChainFixtures.loanFunded(
                    TX,
                    1,
                    BigInteger.valueOf(9),
                    ChainFixtures.LENDER,
                    ChainFixtures.BORROWER,
                    HUGE,
                    1_760_000_100L,
                    1_767_776_100L))
            .orElseThrow();

    assertThat(event.type()).isEqualTo(ChainEventType.LOAN_FUNDED);
    assertThat(event.loanId()).isEqualTo(BigInteger.valueOf(9));
    assertThat(event.payload())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "lender",
                ChainFixtures.LENDER,
                "borrower",
                ChainFixtures.BORROWER,
                "amount",
                HUGE.toString(),
                "fundedAt",
                "1760000100",
                "dueDate",
                "1767776100"));
  }

  @Test
  void decodesLoanRepaidOnTimeAndLate() {
    ChainEvent onTime =
        decode(ChainFixtures.loanRepaid(TX, 0, BigInteger.TEN, HUGE, true)).orElseThrow();
    ChainEvent late =
        decode(ChainFixtures.loanRepaid(TX, 1, BigInteger.TEN, BigInteger.ONE, false))
            .orElseThrow();

    assertThat(onTime.type()).isEqualTo(ChainEventType.LOAN_REPAID);
    assertThat(onTime.payload())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("amountPaid", HUGE.toString(), "onTime", "true"));
    assertThat(late.payload())
        .containsExactlyInAnyOrderEntriesOf(Map.of("amountPaid", "1", "onTime", "false"));
  }

  @Test
  void decodesLoanDefaulted() {
    ChainEvent event =
        decode(
                ChainFixtures.loanDefaulted(
                    TX, 2, BigInteger.valueOf(3), ChainFixtures.BORROWER, HUGE))
            .orElseThrow();

    assertThat(event.type()).isEqualTo(ChainEventType.LOAN_DEFAULTED);
    assertThat(event.loanId()).isEqualTo(BigInteger.valueOf(3));
    assertThat(event.payload())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("borrower", ChainFixtures.BORROWER, "outstandingAmount", HUGE.toString()));
  }

  @Test
  void anAddressInUppercaseDecodesToLowercase() {
    String mixed = "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01";
    ChainEvent event =
        decode(ChainFixtures.loanDefaulted(TX, 0, BigInteger.ONE, mixed, BigInteger.ONE))
            .orElseThrow();

    assertThat(event.payload().get("borrower")).isEqualTo(mixed.toLowerCase());
  }

  @Test
  void aLogOfAnotherContractDecodesToEmpty() {
    ChainFixtures.Log log =
        ChainFixtures.loanRepaid(TX, 0, BigInteger.ONE, BigInteger.ONE, true)
            .withAddress("0x3333333333333333333333333333333333333333");

    assertThat(decode(log)).isEmpty();
  }

  @Test
  void theLoanBookAddressMatchesInAnyCase() {
    ChainFixtures.Log log =
        ChainFixtures.loanRepaid(TX, 0, BigInteger.ONE, BigInteger.ONE, true)
            .withAddress(ChainFixtures.LOAN_BOOK.toUpperCase().replace("0X", "0x"));

    assertThat(decode(log)).isPresent();
  }

  @Test
  void aLogWithAnUnknownTopicDecodesToEmpty() {
    ChainFixtures.Log log =
        ChainFixtures.loanRepaid(TX, 0, BigInteger.ONE, BigInteger.ONE, true)
            .withTopics(List.of("0x" + "11".repeat(32), "0x" + "00".repeat(32)));

    assertThat(decode(log)).isEmpty();
  }

  @Test
  void aRemovedLogDecodesToEmpty() {
    ChainFixtures.Log log = ChainFixtures.loanRepaid(TX, 0, BigInteger.ONE, BigInteger.ONE, true);

    assertThat(events.decode(raw(log, true), new BlockInfo(42, BLOCK_HASH, TIME))).isEmpty();
  }

  @Test
  void aLogWithTruncatedDataDecodesToEmpty() {
    ChainFixtures.Log full =
        ChainFixtures.loanRequested(
            TX, 0, BigInteger.ONE, ChainFixtures.BORROWER, BigInteger.TEN, 1200, 90, 3);

    assertThat(decode(full.withData(full.data().substring(0, 2 + 64 * 2)))).isEmpty();
    assertThat(decode(full.withData("0x"))).isEmpty();
    assertThat(decode(full.withData(full.data().substring(0, full.data().length() - 5)))).isEmpty();
  }

  @Test
  void aLogWithMissingTopicsDecodesToEmpty() {
    ChainFixtures.Log log =
        ChainFixtures.loanFunded(
            TX,
            0,
            BigInteger.ONE,
            ChainFixtures.LENDER,
            ChainFixtures.BORROWER,
            BigInteger.TEN,
            1,
            2);

    assertThat(decode(log.withTopics(log.topics().subList(0, 2)))).isEmpty();
    assertThat(decode(log.withTopics(List.of()))).isEmpty();
  }

  @Test
  void theFourTopic0ConstantsEqualTheHashesOfTheResearch() {
    assertThat(LoanBookEvents.TOPIC_LOAN_REQUESTED)
        .isEqualTo("0x1f62723617d93980723e686276b5de29fb94c025b9327e6226515164c2d53c78");
    assertThat(LoanBookEvents.TOPIC_LOAN_FUNDED)
        .isEqualTo("0xadf85500d5897299b49cd4e5da36fe4ca44902421b481344b28a57ea02d5767a");
    assertThat(LoanBookEvents.TOPIC_LOAN_REPAID)
        .isEqualTo("0x1ccc425b724aaebfa2ad6a4a3b5edcd098c0e29a314a8ef6d17c912007dd5c1d");
    assertThat(LoanBookEvents.TOPIC_LOAN_DEFAULTED)
        .isEqualTo("0x2d6de712d035bfecc1593860af7eae0831e978cab3cd96f778478b1fdc443bc3");
  }
}
