package com.latifi.api.exchangerate.domain;

import java.util.Set;

/**
 * Port to a source of exchange rates. An adapter answers the rate of every requested currency in
 * units of the currency per one US dollar, or throws {@link RateProviderException}. It never
 * returns a partial answer.
 */
public interface ExchangeRateProvider {

  ProviderRates fetch(Set<String> currencies);
}
