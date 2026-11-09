package com.latifi.api.ledger.domain;

import java.util.List;

/** A log exactly as the node returned it, before any decoding. */
public record RawLog(
    String address,
    List<String> topics,
    String data,
    String txHash,
    int logIndex,
    long blockNumber,
    String blockHash,
    boolean removed) {

  public RawLog {
    topics = List.copyOf(topics);
  }
}
