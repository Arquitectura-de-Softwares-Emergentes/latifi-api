package com.latifi.api.shared;

import java.net.URI;

/**
 * Keeps provider URLs out of logs. The path and the query of such a URL can hold an API key, so a
 * log line names only the host.
 */
public final class UrlHosts {

  private static final String INVALID = "invalid-url";

  private UrlHosts() {}

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
