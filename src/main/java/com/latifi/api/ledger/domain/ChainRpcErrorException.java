package com.latifi.api.ledger.domain;

/**
 * The node answered, and the answer was a JSON-RPC error object. A transport failure or a timeout
 * is a plain {@link ChainRpcException}. The difference matters to the log source: a node that
 * rejects a block range can be asked again with a smaller range, a node that is down cannot.
 */
public class ChainRpcErrorException extends ChainRpcException {

  private final int code;

  public ChainRpcErrorException(String message, int code) {
    super(message);
    this.code = code;
  }

  /** The JSON-RPC error code the node sent. */
  public int code() {
    return code;
  }
}
