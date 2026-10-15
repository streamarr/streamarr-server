package com.streamarr.server.domain.streaming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import lombok.NonNull;

/** Media times as decimal seconds, exact to the nanosecond that a {@link Duration} holds. */
public final class DecimalSeconds {

  static final int NANOSECOND_DIGITS = 9;

  private DecimalSeconds() {}

  public static BigDecimal of(@NonNull Duration duration) {
    return BigDecimal.valueOf(duration.toNanos(), NANOSECOND_DIGITS);
  }

  // The seconds must carry at most nine decimal digits.
  static Duration toDuration(BigDecimal seconds) {
    var wholeSeconds = seconds.setScale(0, RoundingMode.FLOOR);
    return Duration.ofSeconds(
        wholeSeconds.longValueExact(),
        seconds.subtract(wholeSeconds).movePointRight(NANOSECOND_DIGITS).longValueExact());
  }
}
