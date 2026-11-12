package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

/**
 * One decoded contract log. The pair {@code (txHash, logIndex)} is its identity.
 *
 * @param payload the non indexed fields as text: addresses in lowercase, integers in decimal
 */
public record ChainEvent(
    String txHash,
    int logIndex,
    long blockNumber,
    String blockHash,
    Instant blockTimestamp,
    ChainEventType type,
    BigInteger loanId,
    Map<String, String> payload) {

  public ChainEvent {
    payload = Map.copyOf(payload);
  }
}
