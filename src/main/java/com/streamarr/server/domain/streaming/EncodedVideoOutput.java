package com.streamarr.server.domain.streaming;

import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import java.util.Comparator;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The planned output of one encoded variant that its codec level must allow: the picture size, the
 * frame rate and the bitrate that the worker caps the encode at.
 */
@Builder
public record EncodedVideoOutput(
    @NonNull String codecFamily, int width, int height, @NonNull Fraction frameRate, long bitrate) {

  // The fitting of an encode to the capability entries keeps the frame rate before the picture
  // size (ADR 0049), then prefers the larger picture, then the higher bitrate.
  static final Comparator<EncodedVideoOutput>
      FASTER_FRAME_RATE_THEN_LARGER_PICTURE_THEN_HIGHER_BITRATE =
          Comparator.<EncodedVideoOutput>comparingDouble(output -> output.frameRate().toDouble())
              .thenComparingLong(EncodedVideoOutput::pictureSize)
              .thenComparingLong(EncodedVideoOutput::bitrate);

  public static class EncodedVideoOutputBuilder {

    /** An encode runs at the probed frame rate, so a source without one has no planned output. */
    public EncodedVideoOutputBuilder probedFrameRate(@NonNull Optional<Fraction> probedFrameRate) {
      return frameRate(
          probedFrameRate.orElseThrow(
              () ->
                  new UnsupportedVideoEncodeException("An encode needs the source's frame rate")));
    }
  }

  int widthInMacroblocks() {
    return Math.ceilDiv(width, 16);
  }

  int heightInMacroblocks() {
    return Math.ceilDiv(height, 16);
  }

  long pictureSize() {
    return (long) width * height;
  }
}
