package com.latifi.api.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class Uint256Test {

  private static final BigInteger TWO_TO_256 = BigInteger.ONE.shiftLeft(256);

  @Test
  void acceptsZero() {
    assertThat(Uint256.parse("0")).isEqualTo(BigInteger.ZERO);
  }

  @Test
  void acceptsOne() {
    assertThat(Uint256.parse("1")).isEqualTo(BigInteger.ONE);
  }

  @Test
  void acceptsTheLargestUint256() {
    BigInteger max = TWO_TO_256.subtract(BigInteger.ONE);
    assertThat(Uint256.parse(max.toString())).isEqualTo(max);
  }

  @Test
  void acceptsLeadingZeros() {
    assertThat(Uint256.parse("007")).isEqualTo(BigInteger.valueOf(7));
  }

  @Test
  void rejectsTwoToThePowerOf256() {
    assertRejected(TWO_TO_256.toString());
  }

  @Test
  void rejectsSeventyNineDigits() {
    assertRejected("1".repeat(79));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"-1", "+1", "1.5", "0x10", "1 0", " 1", "1 ", "abc", "1e3", "١٢٣"})
  void rejectsEveryOtherForm(String text) {
    assertRejected(text);
  }

  private static void assertRejected(String text) {
    assertThatThrownBy(() -> Uint256.parse(text))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
  }
}
