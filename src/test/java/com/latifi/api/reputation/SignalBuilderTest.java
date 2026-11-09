package com.latifi.api.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventType;
import com.latifi.api.ledger.domain.LoanState;
import com.latifi.api.ledger.domain.LoanStatus;
import com.latifi.api.reputation.domain.BorrowerLoanHistory;
import com.latifi.api.reputation.domain.ReputationSignal;
import com.latifi.api.reputation.domain.SignalBuilder;
import com.latifi.api.reputation.domain.SignalType;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The derivation of signals from loans, events and the profile, with no database. */
class SignalBuilderTest {

  private static final long FUNDED_AT = 1_800_000_000L;
  private static final long DAY = 86_400L;
  private static final long FIRST_DUE = FUNDED_AT + 30 * DAY;
  private static final BigInteger PRINCIPAL = BigInteger.valueOf(1_000_000_000L);

  /** Loan 7 of the write-up: 1000000000 units, 1000 bps, 90 days, 3 installments. */
  private static LoanState loan(BigInteger principal, int installments, Instant fundedAt) {
    return new LoanState(
        BigInteger.valueOf(7),
        "0x1111111111111111111111111111111111111111",
        fundedAt == null ? null : "0x2222222222222222222222222222222222222222",
        principal,
        1000,
        90,
        installments,
        fundedAt == null ? LoanStatus.OPEN : LoanStatus.ACTIVE,
        null,
        BigInteger.ZERO,
        0,
        Instant.ofEpochSecond(FUNDED_AT - 100),
        fundedAt,
        null,
        null);
  }

  private static ChainEvent repaid(long block, int logIndex, long amount, boolean onTime, long at) {
    return new ChainEvent(
        "0x" + String.format("%064x", block * 100 + logIndex),
        logIndex,
        block,
        "0x" + String.format("%064x", block),
        Instant.ofEpochSecond(at),
        ChainEventType.LOAN_REPAID,
        BigInteger.valueOf(7),
        Map.of("amountPaid", Long.toString(amount), "onTime", Boolean.toString(onTime)));
  }

  @Test
  void anOnTimeCompletedInstallmentGivesOneSignalWithItsSourceKeyAndPosition() {
    ChainEvent event = repaid(14, 2, 341_552_511L, true, FIRST_DUE - 1_000);
    BorrowerLoanHistory history =
        new BorrowerLoanHistory(
            loan(PRINCIPAL, 3, Instant.ofEpochSecond(FUNDED_AT)), List.of(event));

    List<ReputationSignal> signals = SignalBuilder.build(Optional.empty(), List.of(history));

    assertThat(signals).hasSize(1);
    ReputationSignal signal = signals.get(0);
    assertThat(signal.type()).isEqualTo(SignalType.REPAID_ON_TIME);
    assertThat(signal.sourceKey()).isEqualTo("tx:" + event.txHash() + ":2");
    assertThat(signal.principal()).isEqualTo(PRINCIPAL);
    assertThat(signal.blockNumber()).isEqualTo(14);
    assertThat(signal.logIndex()).isEqualTo(2);
    assertThat(signal.txHash()).isEqualTo(event.txHash());
    assertThat(signal.occurredAt()).isEqualTo(Instant.ofEpochSecond(FIRST_DUE - 1_000));
    assertThat(signal.loanId()).isEqualTo("7");
  }
}
