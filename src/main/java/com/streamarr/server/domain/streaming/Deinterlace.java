package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.NonNull;

/**
 * How an encode deinterlaces an interlaced source, and the output frame rate that follows
 * (streamarr/streamarr-server#653). The stored output frame rate of an interlaced stream counts
 * frames, each of which holds two fields (ADR 0037).
 */
public record Deinterlace(@NonNull FieldOrder fieldOrder, @NonNull DeinterlaceOutput output) {

  // Apple's HLS Authoring Specification item 1.19 forbids frame rates above 60.
  private static final int HIGHEST_FRAMES_PER_SECOND = 60;

  public Deinterlace {
    if (!fieldOrder.isInterlaced()) {
      throw new IllegalArgumentException(
          "Only an interlaced field order deinterlaces, not " + fieldOrder);
    }
  }

  /**
   * One frame for each field, as Apple's item 1.15 recommends for 30i, unless that rate would run
   * above 60 frames per second. A stream without a stored rate keeps one frame for each frame.
   */
  public static Deinterlace of(
      @NonNull FieldOrder fieldOrder, @NonNull Optional<Fraction> storedOutputFrameRate) {
    var output =
        storedOutputFrameRate.filter(Deinterlace::fieldRateFits).isPresent()
            ? DeinterlaceOutput.ONE_FRAME_PER_FIELD
            : DeinterlaceOutput.ONE_FRAME_PER_FRAME;
    return new Deinterlace(fieldOrder, output);
  }

  /** The rate that the deinterlaced encode runs at. */
  public Fraction outputFrameRate(@NonNull Fraction storedOutputFrameRate) {
    return switch (output) {
      case ONE_FRAME_PER_FIELD -> fieldRate(storedOutputFrameRate);
      case ONE_FRAME_PER_FRAME -> storedOutputFrameRate;
    };
  }

  private static boolean fieldRateFits(Fraction frameRate) {
    return 2L * frameRate.numerator() <= (long) HIGHEST_FRAMES_PER_SECOND * frameRate.denominator();
  }

  // 30000/1001 becomes 60000/1001, so the rate stays exact.
  private static Fraction fieldRate(Fraction frameRate) {
    return new Fraction(Math.multiplyExact(frameRate.numerator(), 2), frameRate.denominator());
  }
}
