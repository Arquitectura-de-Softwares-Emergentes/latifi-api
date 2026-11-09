package com.latifi.api.exchangerate.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * What a provider answered: the instant the rates were published and one rate per currency, in
 * units of the currency per one US dollar.
 */
public record ProviderRates(Instant asOf, Map<String, BigDecimal> rates) {

  public ProviderRates {
    rates = Map.copyOf(rates);
  }
}
