package com.latifi.api.exchangerate;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.exchangerate.domain.Conversion;
import com.latifi.api.shared.Uint256;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** The money rules of the conversion: exact arithmetic, half-even rounding, plain output. */
class ConversionTest {

  private static String value(String units, String rate, String currency) {
    return Conversion.formatValue(
        Conversion.convert(new BigInteger(units), new BigDecimal(rate), currency));
  }

  @Test
  void convertsTwoHundredFiftyDollarsToSoles() {
    assertThat(value("250000000", "3.438038", "PEN")).isEqualTo("859.51");
  }

  @Test
  void keepsTwoDecimalsForPesosWithCents() {
    assertThat(value("2500000", "1", "PEN")).isEqualTo("2.50");
  }

  @Test
  void dropsAllDecimalsForChileanPesos() {
    assertThat(value("2500000", "1", "CLP")).isEqualTo("2");
  }

  @Test
  void roundsAnExactHalfToTheEvenNeighbour() {
    // 3.5 rounds up to 4 and 2.5 rounds down to 2: half-even, not half-up.
    assertThat(value("3500000", "1", "CLP")).isEqualTo("4");
    assertThat(value("2500000", "1", "CLP")).isEqualTo("2");
    assertThat(value("12500", "1", "PEN")).isEqualTo("0.01");
    assertThat(value("17500", "1", "PEN")).isEqualTo("0.02");
  }

  @Test
  void roundsEachSupportedCurrencyAtItsOwnPrecision() {
    assertThat(value("250000000", "3239.854719", "COP")).isEqualTo("809963.68");
    assertThat(value("250000000", "18.652374", "MXN")).isEqualTo("4663.09");
    assertThat(value("250000000", "1545.3974", "ARS")).isEqualTo("386349.35");
    assertThat(value("250000000", "5.502723", "BRL")).isEqualTo("1375.68");
    assertThat(value("250000000", "974.378765", "CLP")).isEqualTo("243595");
  }

  @Test
  void convertsTheLargestUint256WithoutOverflowOrExponent() {
    String result = value(Uint256.MAX.toString(), "974.378765", "CLP");
    assertThat(result).matches("[0-9]+").hasSizeGreaterThan(70);
    BigDecimal expected =
        new BigDecimal(Uint256.MAX)
            .movePointLeft(6)
            .multiply(new BigDecimal("974.378765"))
            .setScale(0, RoundingMode.HALF_EVEN);
    assertThat(result).isEqualTo(expected.toPlainString());
  }

  @Test
  void convertsZeroToZeroWithTheCurrencyPrecision() {
    assertThat(value("0", "3.438038", "PEN")).isEqualTo("0.00");
    assertThat(value("0", "974.378765", "CLP")).isEqualTo("0");
  }

  @Test
  void writesRatesWithoutTrailingZerosOrExponent() {
    assertThat(Conversion.formatRate(new BigDecimal("1000"))).isEqualTo("1000");
    assertThat(Conversion.formatRate(new BigDecimal("1000.0000000000"))).isEqualTo("1000");
    assertThat(Conversion.formatRate(new BigDecimal("3.4380380000"))).isEqualTo("3.438038");
    assertThat(Conversion.formatRate(new BigDecimal("1E+3"))).isEqualTo("1000");
  }
}
