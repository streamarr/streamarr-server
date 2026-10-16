package com.streamarr.server.domain.streaming;

import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import java.util.OptionalDouble;
import lombok.Builder;
import lombok.NonNull;

/**
 * The planned output of one encoded variant that its codec level must allow: the picture size, the
 * frame rate and the bitrate that the worker caps the encode at.
 */
@Builder
public record EncodedVideoOutput(
    @NonNull String codecFamily, int width, int height, double frameRate, long bitrate) {

  public static class EncodedVideoOutputBuilder {

    /** An encode runs at the probed frame rate, so a source without one has no planned output. */
    public EncodedVideoOutputBuilder probedFrameRate(@NonNull OptionalDouble probedFrameRate) {
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
