package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("ratesDividedByWholeNumbers")
  @DisplayName("Should keep the quotient in lowest terms when a rate is divided by a whole number")
  void shouldKeepTheQuotientInLowestTermsWhenARateIsDividedByAWholeNumber(DividedRate divided) {
    var quotient = divided.rate().dividedBy(divided.divisor());

    assertThat(quotient).isEqualTo(divided.quotient());
  }

  static Stream<DividedRate> ratesDividedByWholeNumbers() {
    return Stream.of(
        DividedRate.builder()
            .rate(new Fraction(60, 1))
            .divisor(2)
            .quotient(new Fraction(30, 1))
            .build(),
        DividedRate.builder()
            .rate(new Fraction(60_000, 1_001))
            .divisor(2)
            .quotient(new Fraction(30_000, 1_001))
            .build(),
        DividedRate.builder()
            .rate(new Fraction(60_000, 1_001))
            .divisor(3)
            .quotient(new Fraction(20_000, 1_001))
            .build());
  }

  @Builder
  record DividedRate(Fraction rate, int divisor, Fraction quotient) {}
}
