package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Retry-After header")
class RetryAfterTest {

  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

  @Test
  @DisplayName("Should read the delay in seconds when the header holds a number")
  void shouldReadTheDelayInSecondsWhenTheHeaderHoldsANumber() {
    assertThat(RetryAfter.parse(Optional.of("120"), NOW)).contains(Duration.ofSeconds(120));
  }

  @Test
  @DisplayName("Should read the delay until the date when the header holds an HTTP date")
  void shouldReadTheDelayUntilTheDateWhenTheHeaderHoldsAnHttpDate() {
    assertThat(RetryAfter.parse(Optional.of("Wed, 14 Oct 2026 12:01:30 GMT"), NOW))
        .contains(Duration.ofSeconds(90));
  }

  @Test
  @DisplayName("Should read no delay when the date has passed")
  void shouldReadNoDelayWhenTheDateHasPassed() {
    assertThat(RetryAfter.parse(Optional.of("Wed, 14 Oct 2026 11:59:00 GMT"), NOW))
        .contains(Duration.ZERO);
  }

  @ParameterizedTest
  @ValueSource(strings = {"soon", "-5", "", "99999999999999999999"})
  @DisplayName("Should read no delay when the header cannot be parsed")
  void shouldReadNoDelayWhenTheHeaderCannotBeParsed(String header) {
    assertThat(RetryAfter.parse(Optional.of(header), NOW)).isEmpty();
  }

  @Test
  @DisplayName("Should read no delay when the host sends no header")
  void shouldReadNoDelayWhenTheHostSendsNoHeader() {
    assertThat(RetryAfter.parse(Optional.empty(), NOW)).isEmpty();
  }

  @Test
  @DisplayName("Should reject a parse when its header or clock reading is missing")
  void shouldRejectAParseWhenItsHeaderOrClockReadingIsMissing() {
    var noHeader = Optional.<String>empty();

    assertThatNullPointerException().isThrownBy(() -> RetryAfter.parse(null, NOW));
    assertThatNullPointerException().isThrownBy(() -> RetryAfter.parse(noHeader, null));
  }
}
