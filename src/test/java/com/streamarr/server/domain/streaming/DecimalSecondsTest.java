package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Decimal Seconds Tests")
class DecimalSecondsTest {

  @Test
  @DisplayName("Should keep every nanosecond when a duration becomes decimal seconds")
  void shouldKeepEveryNanosecondWhenADurationBecomesDecimalSeconds() {
    var duration = Duration.ofSeconds(6, 6_000_001);

    assertThat(DecimalSeconds.of(duration)).isEqualTo(new BigDecimal("6.006000001"));
  }

  @Test
  @DisplayName("Should reject a missing duration when converting it to decimal seconds")
  void shouldRejectAMissingDurationWhenConvertingItToDecimalSeconds() {
    assertThatNullPointerException().isThrownBy(() -> DecimalSeconds.of(null));
  }
}
