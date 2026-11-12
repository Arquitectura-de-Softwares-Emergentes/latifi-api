package com.latifi.api.ledger.domain;

/**
 * The node failed or answered with an error. The message never carries the node URL, because the
 * URL can hold an API key.
 */
public class ChainRpcException extends RuntimeException {

  public ChainRpcException(String message) {
    super(message);
  }
}
