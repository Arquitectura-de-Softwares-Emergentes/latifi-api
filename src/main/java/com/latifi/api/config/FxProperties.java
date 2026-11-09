package com.latifi.api.config;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exchange rate settings bound to {@code latifi.fx}. A value out of range throws at startup, which
 * stops the application context.
 *
 * @param providerUrl address of the rate provider; the text {@code {apiKey}} stands for the key
 * @param apiKey optional key of the provider, read from the environment and never logged
 * @param supported ISO 4217 codes of the local currencies the API converts to
 * @param cacheTtl age at which a cached rate counts as expired
 * @param connectTimeout limit to open the connection to the provider
 * @param readTimeout limit to wait for the answer of the provider
 * @param retryBackoff pause after a failed refresh before the provider is called again
 */
@ConfigurationProperties(prefix = "latifi.fx")
public record FxProperties(
    String providerUrl,
    String apiKey,
    List<String> supported,
    Duration cacheTtl,
    Duration connectTimeout,
    Duration readTimeout,
    Duration retryBackoff) {

  /** The text of the provider URL that the key replaces. */
  public static final String KEY_PLACEHOLDER = "{apiKey}";

  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

  public FxProperties {
    if (providerUrl == null || providerUrl.isBlank()) {
      throw new IllegalArgumentException("latifi.fx.provider-url must not be blank");
    }
    requireHttpUrl(providerUrl);
    if (apiKey == null) {
      apiKey = "";
    }
    if (providerUrl.contains(KEY_PLACEHOLDER) && apiKey.isBlank()) {
      throw new IllegalArgumentException(
          "latifi.fx.provider-url holds " + KEY_PLACEHOLDER + " but latifi.fx.api-key is blank");
    }
    if (supported == null || supported.isEmpty()) {
      throw new IllegalArgumentException("latifi.fx.supported must list at least one currency");
    }
    for (String code : supported) {
      if (code == null || !CURRENCY.matcher(code).matches()) {
        throw new IllegalArgumentException(
            "latifi.fx.supported entries must be three uppercase letters");
      }
    }
    supported = List.copyOf(supported);
    requirePositive(cacheTtl, "cache-ttl");
    requirePositive(connectTimeout, "connect-timeout");
    requirePositive(readTimeout, "read-timeout");
    requirePositive(retryBackoff, "retry-backoff");
  }

  /** The URL a request goes to, with the encoded key put in place of the placeholder. */
  public String resolvedUrl() {
    return providerUrl.replace(KEY_PLACEHOLDER, URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
  }

  /** Leaves the URL and the key out, so no log line or error message can carry them. */
  @Override
  public String toString() {
    return "FxProperties[supported=" + supported + ", cacheTtl=" + cacheTtl + "]";
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException("latifi.fx." + name + " must be positive");
    }
  }

  /** Checks the shape of the URL without echoing it, because it can hold a key. */
  private static void requireHttpUrl(String url) {
    try {
      // The placeholder has braces, which a URI rejects, so test with a stand-in.
      URI uri = URI.create(url.replace(KEY_PLACEHOLDER, "key"));
      boolean httpScheme = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
      if (!httpScheme || uri.getHost() == null) {
        throw new IllegalArgumentException("latifi.fx.provider-url must be an http or https URL");
      }
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("latifi.fx.provider-url must be an http or https URL");
    }
  }
}
