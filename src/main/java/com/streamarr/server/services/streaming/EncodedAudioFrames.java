package com.streamarr.server.services.streaming;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * The frames that a session's audio encoder writes: frames of {@code frameSamples} at {@code
 * sampleRate}, after {@code primingSamples} of priming at the start of the stream.
 */
record EncodedAudioFrames(long frameSamples, long primingSamples, int sampleRate) {

  private static final int NANOSECOND_DIGITS = 9;

  /** The frames that cover this many seconds, with the priming samples when the stream starts. */
  BigInteger over(BigDecimal seconds, boolean startsTheStream) {
    var samples = seconds.multiply(BigDecimal.valueOf(sampleRate));
    if (startsTheStream) {
      samples = samples.add(BigDecimal.valueOf(primingSamples));
    }

    return samples
        .divide(BigDecimal.valueOf(frameSamples), 0, RoundingMode.CEILING)
        .toBigIntegerExact();
  }

  /** How long this many frames play, in seconds, rounded up to nanoseconds. */
  BigDecimal secondsOf(BigInteger frames) {
    return new BigDecimal(frames.multiply(BigInteger.valueOf(frameSamples)))
        .divide(BigDecimal.valueOf(sampleRate), NANOSECOND_DIGITS, RoundingMode.CEILING);
  }
}
