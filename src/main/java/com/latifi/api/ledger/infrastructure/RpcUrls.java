package com.latifi.api.ledger.infrastructure;

import java.net.URI;

/** Keeps node URLs out of logs. The path and the query of a provider URL can hold an API key. */
public final class RpcUrls {

  private static final String INVALID = "invalid-url";

  private RpcUrls() {}

  /** Returns only the host of {@code url}, or {@code invalid-url} when it has none. */
  public static String redact(String url) {
    if (url == null || url.isBlank()) {
      return INVALID;
    }
    try {
      String host = URI.create(url.trim()).getHost();
      return host == null || host.isBlank() ? INVALID : host;
    } catch (IllegalArgumentException e) {
      return INVALID;
    }
  }
}
