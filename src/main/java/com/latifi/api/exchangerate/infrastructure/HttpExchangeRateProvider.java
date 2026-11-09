package com.latifi.api.exchangerate.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.config.FxProperties;
import com.latifi.api.exchangerate.domain.ExchangeRateProvider;
import com.latifi.api.exchangerate.domain.ProviderRates;
import com.latifi.api.exchangerate.domain.RateProviderException;
import com.latifi.api.shared.UrlHosts;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads rates from an HTTP provider whose answer has the shape of open.er-api.com: a JSON object
 * with {@code result}, {@code base_code}, {@code time_last_update_unix} and a {@code rates} map.
 * The URL and the optional key come from {@link FxProperties}. The adapter rejects the whole answer
 * unless every requested currency has a positive rate, and it never logs more than the host of the
 * URL.
 */
@Component
public class HttpExchangeRateProvider implements ExchangeRateProvider {

  private static final Logger LOG = LoggerFactory.getLogger(HttpExchangeRateProvider.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The cache column is numeric(24,10), so a rate keeps 10 decimals and stays below 10^14. */
  private static final int RATE_SCALE = 10;

  private static final BigDecimal RATE_LIMIT = BigDecimal.TEN.pow(14);

  private final RestClient client;
  private final URI uri;
  private final String host;

  public HttpExchangeRateProvider(FxProperties properties) {
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(properties.readTimeout());
    this.client = RestClient.builder().requestFactory(factory).build();
    this.uri = URI.create(properties.resolvedUrl());
    // Only the host leaves this constructor; the path and the key stay in the URI.
    this.host = UrlHosts.redact(uri.toString());
  }

  @Override
  public ProviderRates fetch(Set<String> currencies) {
    String body = call();
    try {
      return parse(body, currencies);
    } catch (RateProviderException e) {
      LOG.warn("The rate provider {} sent an answer that was rejected: {}", host, e.getMessage());
      throw e;
    }
  }

  private String call() {
    try {
      ResponseEntity<String> response = client.get().uri(uri).retrieve().toEntity(String.class);
      if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
        throw new RateProviderException("The provider answered HTTP " + response.getStatusCode());
      }
      return response.getBody();
    } catch (RateProviderException e) {
      LOG.warn("The rate provider {} failed: {}", host, e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      // The message of a client exception holds the request URL, so only the class is kept.
      String failure = e.getClass().getSimpleName();
      LOG.warn("The rate provider {} failed: {}", host, failure);
      throw new RateProviderException("The provider call failed: " + failure);
    }
  }

  private static ProviderRates parse(String body, Set<String> currencies) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (IOException e) {
      throw new RateProviderException("The answer is not JSON");
    }
    if (root == null || !root.isObject()) {
      throw new RateProviderException("The answer is not a JSON object");
    }
    if (!"success".equals(text(root, "result"))) {
      throw new RateProviderException("The result is not success");
    }
    if (!"USD".equals(text(root, "base_code"))) {
      throw new RateProviderException("The base currency is not USD");
    }
    JsonNode time = root.get("time_last_update_unix");
    if (time == null || !time.isIntegralNumber() || !time.canConvertToLong()) {
      throw new RateProviderException("The update time is missing or not a number");
    }
    Instant asOf;
    try {
      asOf = Instant.ofEpochSecond(time.longValue());
    } catch (RuntimeException e) {
      throw new RateProviderException("The update time is out of range");
    }
    JsonNode rates = root.get("rates");
    if (rates == null || !rates.isObject()) {
      throw new RateProviderException("The rates object is missing");
    }
    Map<String, BigDecimal> parsed = new LinkedHashMap<>();
    for (String currency : currencies) {
      parsed.put(currency, rate(rates.get(currency), currency));
    }
    return new ProviderRates(asOf, parsed);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static BigDecimal rate(JsonNode node, String currency) {
    if (node == null || node.isNull()) {
      throw new RateProviderException("The rate of " + currency + " is missing");
    }
    if (!node.isNumber()) {
      throw new RateProviderException("The rate of " + currency + " is not a number");
    }
    BigDecimal value = node.decimalValue();
    if (value.signum() <= 0 || value.compareTo(RATE_LIMIT) >= 0) {
      throw new RateProviderException("The rate of " + currency + " is out of range");
    }
    BigDecimal stored = value.setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    if (stored.signum() <= 0) {
      throw new RateProviderException("The rate of " + currency + " is too small");
    }
    return stored;
  }
}
