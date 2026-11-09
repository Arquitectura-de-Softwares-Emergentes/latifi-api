package com.latifi.api.exchangerate.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A stored rate. {@code asOf} is the instant the provider gave, {@code fetchedAt} is our clock when
 * we stored the rate.
 */
public record CachedRate(String currency, BigDecimal rate, Instant asOf, Instant fetchedAt) {}
