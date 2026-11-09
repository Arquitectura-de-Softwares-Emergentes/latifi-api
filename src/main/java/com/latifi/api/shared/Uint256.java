package com.latifi.api.shared;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Parses the uint256 values that clients send as digit strings. */
public final class Uint256 {

  /** The largest value of a uint256, two to the power of 256 minus one. */
  public static final BigInteger MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

  private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,78}$");

  private Uint256() {}

  /**
   * Accepts 1 to 78 decimal digits whose value fits in a uint256. Any other text, including signs,
   * decimals, hex, spaces and an empty string, answers 400 VALIDATION_FAILED.
   */
  public static BigInteger parse(String text) {
    if (text == null || !DIGITS.matcher(text).matches()) {
      throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
    BigInteger value = new BigInteger(text);
    if (value.compareTo(MAX) > 0) {
      throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
    return value;
  }
}
