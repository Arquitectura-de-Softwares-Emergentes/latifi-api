package com.latifi.api.exchangerate.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Converts stablecoin base units to a local currency. The stablecoin has 6 decimals and counts as
 * one US dollar. Only {@link BigInteger} and {@link BigDecimal} take part, so no amount loses
 * precision at any size.
 */
public final class Conversion {

  private static final int STABLECOIN_DECIMALS = 6;

  private Conversion() {}

  /**
   * Returns {@code baseUnits / 10^6 * rate}, rounded half-even to the fraction digits of the
   * currency. Chilean pesos have none, so the result of {@code CLP} is a whole number.
   */
  public static BigDecimal convert(BigInteger baseUnits, BigDecimal rate, String currencyCode) {
    int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
    return new BigDecimal(baseUnits)
        .movePointLeft(STABLECOIN_DECIMALS)
        .multiply(rate)
        .setScale(digits, RoundingMode.HALF_EVEN);
  }

  /** Writes a converted value as a plain decimal, never with an exponent. */
  public static String formatValue(BigDecimal value) {
    return value.toPlainString();
  }

  /** Writes a rate as a plain decimal without trailing zeros. */
  public static String formatRate(BigDecimal rate) {
    return rate.stripTrailingZeros().toPlainString();
  }
}
