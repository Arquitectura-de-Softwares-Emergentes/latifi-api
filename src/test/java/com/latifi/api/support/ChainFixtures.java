package com.latifi.api.support;

import com.latifi.api.ledger.infrastructure.LoanBookEvents;
import java.math.BigInteger;
import java.util.List;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;

/**
 * Builds the logs of the four v0 events the way the EVM lays them out: topic words of 32 bytes,
 * addresses padded on the left, non indexed fields as consecutive words in {@code data}.
 */
public final class ChainFixtures {

  /** LoanBook on Amoy, from deployments/amoy.json of the contracts repository. */
  public static final String LOAN_BOOK = "0xccb8ad15a4954138c987a77924ceab468a843266";

  public static final String BORROWER = "0x1111111111111111111111111111111111111111";
  public static final String LENDER = "0x2222222222222222222222222222222222222222";

  private ChainFixtures() {}

  /** A log before the node places it in a block. */
  public record Log(String address, List<String> topics, String data, String txHash, int logIndex) {

    public Log {
      topics = List.copyOf(topics);
    }

    public Log withAddress(String other) {
      return new Log(other, topics, data, txHash, logIndex);
    }

    public Log withTopics(List<String> other) {
      return new Log(address, other, data, txHash, logIndex);
    }

    public Log withData(String other) {
      return new Log(address, topics, other, txHash, logIndex);
    }
  }

  /** A transaction hash that is unique per seed. */
  public static String txHash(long seed) {
    return "0x" + String.format("%064x", seed);
  }

  public static Log loanRequested(
      String txHash,
      int logIndex,
      BigInteger loanId,
      String borrower,
      BigInteger amount,
      int interestRateBps,
      long termDays,
      int installments) {
    return new Log(
        LOAN_BOOK,
        List.of(
            LoanBookEvents.TOPIC_LOAN_REQUESTED,
            word(new Uint256(loanId)),
            word(address(borrower))),
        data(
            new Uint256(amount),
            new Uint16(interestRateBps),
            new Uint32(termDays),
            new Uint8(installments)),
        txHash,
        logIndex);
  }

  public static Log loanFunded(
      String txHash,
      int logIndex,
      BigInteger loanId,
      String lender,
      String borrower,
      BigInteger amount,
      long fundedAt,
      long dueDate) {
    return new Log(
        LOAN_BOOK,
        List.of(
            LoanBookEvents.TOPIC_LOAN_FUNDED,
            word(new Uint256(loanId)),
            word(address(lender)),
            word(address(borrower))),
        data(new Uint256(amount), new Uint64(fundedAt), new Uint64(dueDate)),
        txHash,
        logIndex);
  }

  public static Log loanRepaid(
      String txHash, int logIndex, BigInteger loanId, BigInteger amountPaid, boolean onTime) {
    return new Log(
        LOAN_BOOK,
        List.of(LoanBookEvents.TOPIC_LOAN_REPAID, word(new Uint256(loanId))),
        data(new Uint256(amountPaid), new Bool(onTime)),
        txHash,
        logIndex);
  }

  public static Log loanDefaulted(
      String txHash, int logIndex, BigInteger loanId, String borrower, BigInteger outstanding) {
    return new Log(
        LOAN_BOOK,
        List.of(
            LoanBookEvents.TOPIC_LOAN_DEFAULTED,
            word(new Uint256(loanId)),
            word(address(borrower))),
        data(new Uint256(outstanding)),
        txHash,
        logIndex);
  }

  private static Address address(String value) {
    return new Address(value);
  }

  private static String word(Type value) {
    return "0x" + TypeEncoder.encode(value);
  }

  private static String data(Type... values) {
    StringBuilder out = new StringBuilder("0x");
    for (Type value : values) {
      out.append(TypeEncoder.encode(value));
    }
    return out.toString();
  }
}
