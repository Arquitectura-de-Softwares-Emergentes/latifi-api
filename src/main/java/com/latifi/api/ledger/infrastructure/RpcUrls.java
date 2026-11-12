package com.latifi.api.ledger.infrastructure;

import com.latifi.api.shared.UrlHosts;

/** Keeps node URLs out of logs. The path and the query of a provider URL can hold an API key. */
public final class RpcUrls {

  private RpcUrls() {}

  /** Returns only the host of {@code url}, or {@code invalid-url} when it has none. */
  public static String redact(String url) {
    return UrlHosts.redact(url);
  }
}
