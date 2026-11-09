package com.latifi.api.ledger.infrastructure;

import com.latifi.api.ledger.domain.BlockInfo;
import com.latifi.api.ledger.domain.ChainEvent;
import com.latifi.api.ledger.domain.ChainEventType;
import com.latifi.api.ledger.domain.RawLog;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;

/**
 * The four LoanBook events of the frozen v0 ABI, their topic0 values and the decoder that turns a
 * node log into a {@link ChainEvent}. {@code AbiDriftTest} compares these definitions with the ABI
 * file of the contracts repository.
 */
public class LoanBookEvents {

  private static final Logger LOG = LoggerFactory.getLogger(LoanBookEvents.class);

  public static final Event LOAN_REQUESTED =
      new Event(
          "LoanRequested",
          List.of(
              TypeReference.create(Uint256.class, true),
              TypeReference.create(Address.class, true),
              TypeReference.create(Uint256.class),
              TypeReference.create(Uint16.class),
              TypeReference.create(Uint32.class),
              TypeReference.create(Uint8.class)));

  public static final Event LOAN_FUNDED =
      new Event(
          "LoanFunded",
          List.of(
              TypeReference.create(Uint256.class, true),
              TypeReference.create(Address.class, true),
              TypeReference.create(Address.class, true),
              TypeReference.create(Uint256.class),
              TypeReference.create(Uint64.class),
              TypeReference.create(Uint64.class)));

  public static final Event LOAN_REPAID =
      new Event(
          "LoanRepaid",
          List.of(
              TypeReference.create(Uint256.class, true),
              TypeReference.create(Uint256.class),
              TypeReference.create(Bool.class)));

  public static final Event LOAN_DEFAULTED =
      new Event(
          "LoanDefaulted",
          List.of(
              TypeReference.create(Uint256.class, true),
              TypeReference.create(Address.class, true),
              TypeReference.create(Uint256.class)));

  public static final String TOPIC_LOAN_REQUESTED = EventEncoder.encode(LOAN_REQUESTED);
  public static final String TOPIC_LOAN_FUNDED = EventEncoder.encode(LOAN_FUNDED);
  public static final String TOPIC_LOAN_REPAID = EventEncoder.encode(LOAN_REPAID);
  public static final String TOPIC_LOAN_DEFAULTED = EventEncoder.encode(LOAN_DEFAULTED);

  /** The four topic0 values in a fixed order, used as alternatives in the node filter. */
  public static final List<String> ALL_TOPICS =
      List.of(TOPIC_LOAN_REQUESTED, TOPIC_LOAN_FUNDED, TOPIC_LOAN_REPAID, TOPIC_LOAN_DEFAULTED);

  private final String loanBookAddress;

  public LoanBookEvents(String loanBookAddress) {
    this.loanBookAddress = loanBookAddress.toLowerCase(Locale.ROOT);
  }

  /**
   * Decodes one log.
   *
   * @return empty for a log of another contract, another topic0, a removed log or data that does
   *     not decode. The undecodable case logs a warning with the transaction hash and the log
   *     index, never the data.
   */
  public Optional<ChainEvent> decode(RawLog log, BlockInfo block) {
    if (log.removed()
        || log.address() == null
        || !loanBookAddress.equals(log.address().toLowerCase(Locale.ROOT))
        || log.topics().isEmpty()) {
      return Optional.empty();
    }
    String topic0 = log.topics().get(0).toLowerCase(Locale.ROOT);
    try {
      if (topic0.equals(TOPIC_LOAN_REQUESTED)) {
        return Optional.of(requested(log, block));
      }
      if (topic0.equals(TOPIC_LOAN_FUNDED)) {
        return Optional.of(funded(log, block));
      }
      if (topic0.equals(TOPIC_LOAN_REPAID)) {
        return Optional.of(repaid(log, block));
      }
      if (topic0.equals(TOPIC_LOAN_DEFAULTED)) {
        return Optional.of(defaulted(log, block));
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.warn(
          "Skipping a log that does not decode: tx {} log {} ({})",
          log.txHash(),
          log.logIndex(),
          e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private ChainEvent requested(RawLog log, BlockInfo block) {
    List<Type> data = data(log, LOAN_REQUESTED, 4);
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("borrower", addressTopic(log, 2));
    payload.put("amount", number(data.get(0)));
    payload.put("interestRateBps", number(data.get(1)));
    payload.put("termDays", number(data.get(2)));
    payload.put("installments", number(data.get(3)));
    return event(log, block, ChainEventType.LOAN_REQUESTED, 3, payload);
  }

  private ChainEvent funded(RawLog log, BlockInfo block) {
    List<Type> data = data(log, LOAN_FUNDED, 3);
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("lender", addressTopic(log, 2));
    payload.put("borrower", addressTopic(log, 3));
    payload.put("amount", number(data.get(0)));
    payload.put("fundedAt", number(data.get(1)));
    payload.put("dueDate", number(data.get(2)));
    return event(log, block, ChainEventType.LOAN_FUNDED, 4, payload);
  }

  private ChainEvent repaid(RawLog log, BlockInfo block) {
    List<Type> data = data(log, LOAN_REPAID, 2);
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("amountPaid", number(data.get(0)));
    payload.put("onTime", String.valueOf(((Bool) data.get(1)).getValue()));
    return event(log, block, ChainEventType.LOAN_REPAID, 2, payload);
  }

  private ChainEvent defaulted(RawLog log, BlockInfo block) {
    List<Type> data = data(log, LOAN_DEFAULTED, 1);
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("borrower", addressTopic(log, 2));
    payload.put("outstandingAmount", number(data.get(0)));
    return event(log, block, ChainEventType.LOAN_DEFAULTED, 3, payload);
  }

  private static ChainEvent event(
      RawLog log,
      BlockInfo block,
      ChainEventType type,
      int expectedTopics,
      Map<String, String> payload) {
    if (log.topics().size() != expectedTopics) {
      throw new IllegalArgumentException("unexpected topic count");
    }
    BigInteger loanId =
        (BigInteger)
            FunctionReturnDecoder.decodeIndexedValue(
                    log.topics().get(1), TypeReference.create(Uint256.class))
                .getValue();
    return new ChainEvent(
        log.txHash(),
        log.logIndex(),
        log.blockNumber(),
        block.hash(),
        block.timestamp(),
        type,
        loanId,
        payload);
  }

  /** Decodes the non indexed fields and insists that all of them are present. */
  private static List<Type> data(RawLog log, Event event, int expected) {
    // Every field of the v0 events is a static type, so the data is exactly one word per field.
    if (log.data() == null || log.data().length() != 2 + 64 * expected) {
      throw new IllegalArgumentException("data has the wrong length");
    }
    List<Type> values = FunctionReturnDecoder.decode(log.data(), event.getNonIndexedParameters());
    if (values.size() != expected) {
      throw new IllegalArgumentException("truncated data");
    }
    return values;
  }

  private static String addressTopic(RawLog log, int topicIndex) {
    Address address =
        (Address)
            FunctionReturnDecoder.decodeIndexedValue(
                log.topics().get(topicIndex), TypeReference.create(Address.class));
    return address.getValue().toLowerCase(Locale.ROOT);
  }

  private static String number(Type value) {
    return ((BigInteger) value.getValue()).toString();
  }
}
