package com.latifi.api.shared;

import java.util.regex.Pattern;
import org.web3j.crypto.Keys;

/** A wallet address. Stored and compared in lowercase, shown to people in EIP-55 form. */
public final class EthAddress {

  private static final Pattern FORMAT = Pattern.compile("^0x[0-9a-fA-F]{40}$");

  private final String lowercase;

  private EthAddress(String lowercase) {
    this.lowercase = lowercase;
  }

  /** Accepts exactly {@code 0x} plus 40 hex digits in any case. */
  public static EthAddress parse(String value) {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new ApiException(ApiErrorCode.INVALID_ADDRESS);
    }
    return new EthAddress(value.toLowerCase(java.util.Locale.ROOT));
  }

  public String lowercase() {
    return lowercase;
  }

  public String checksum() {
    return Keys.toChecksumAddress(lowercase);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EthAddress that && lowercase.equals(that.lowercase);
  }

  @Override
  public int hashCode() {
    return lowercase.hashCode();
  }

  @Override
  public String toString() {
    return lowercase;
  }
}
