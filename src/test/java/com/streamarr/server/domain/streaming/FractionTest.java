package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Fraction Tests")
class FractionTest {

  @ParameterizedTest
  @CsvSource({"0, 1001", "-24000, 1001", "24000, 0", "24000, -1001"})
  @DisplayName("Should refuse a fraction when a term is not positive")
  void shouldRefuseAFractionWhenATermIsNotPositive(int numerator, int denominator) {
    assertThatThrownBy(() -> new Fraction(numerator, denominator))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should divide the numerator by the denominator when a caller needs a decimal")
  void shouldDivideTheNumeratorByTheDenominatorWhenACallerNeedsADecimal() {
    var filmRate = new Fraction(24_000, 1_001);

    assertThat(filmRate.toDouble()).isEqualTo(24_000.0 / 1_001);
  }

  @ParameterizedTest
  @CsvSource({"60, 1, 2, 30, 1", "60000, 1001, 2, 30000, 1001", "60000, 1001, 3, 20000, 1001"})
  @DisplayName("Should keep the quotient in lowest terms when a rate is divided by a whole number")
  void shouldKeepTheQuotientInLowestTermsWhenARateIsDividedByAWholeNumber(
      int numerator, int denominator, int divisor, int expectedNumerator, int expectedDenominator) {
    var quotient = new Fraction(numerator, denominator).dividedBy(divisor);

    assertThat(quotient).isEqualTo(new Fraction(expectedNumerator, expectedDenominator));
  }
}
