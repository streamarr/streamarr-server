package com.streamarr.server.domain.streaming;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * The keyframes of a source's chosen video stream in decode-time order. Times count ticks of {@code
 * timeBaseNumerator / timeBaseDenominator} seconds.
 */
@Builder
public record KeyframePositions(
    int streamIndex,
    int timeBaseNumerator,
    int timeBaseDenominator,
    @NonNull KeyframePositionSource source,
    @NonNull List<KeyframePosition> keyframes) {

  private static final int NANOSECOND_DIGITS = 9;

  public KeyframePositions {
    if (streamIndex < 0 || timeBaseNumerator <= 0 || timeBaseDenominator <= 0) {
      throw new IllegalArgumentException(
          "Keyframe positions need a stream index and a positive time base");
    }

    if (keyframes.isEmpty()) {
      throw new IllegalArgumentException("Keyframe positions need at least one keyframe");
    }

    keyframes = List.copyOf(keyframes);
  }

  /**
   * The time that this many ticks of the time base span, rounded to whole nanoseconds.
   *
   * @throws ArithmeticException when the time does not fit a {@link Duration}
   */
  public Duration timeOf(long ticks, @NonNull RoundingMode rounding) {
    var seconds =
        new BigDecimal(BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(timeBaseNumerator)))
            .divide(BigDecimal.valueOf(timeBaseDenominator), NANOSECOND_DIGITS, rounding);
    var wholeSeconds = seconds.setScale(0, RoundingMode.FLOOR);
    return Duration.ofSeconds(
        wholeSeconds.longValueExact(),
        seconds.subtract(wholeSeconds).movePointRight(NANOSECOND_DIGITS).longValueExact());
  }
}
