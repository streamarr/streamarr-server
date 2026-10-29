package com.streamarr.server.domain.streaming;

import com.streamarr.server.exceptions.ContradictoryDynamicRangeException;
import lombok.NonNull;

/**
 * The dynamic range of one video stream, as ADR 0036 defines it. A compatible base of NONE requires
 * Dolby Vision to be present: without it, the worker contract names that base UNSPECIFIED.
 */
public record VideoDynamicRange(
    @NonNull CompatibleBase compatibleBase,
    @NonNull Presence hdr10Plus,
    @NonNull DolbyVision dolbyVision) {

  /**
   * Checks the rule for a compatible base of NONE.
   *
   * @throws ContradictoryDynamicRangeException when the compatible base is NONE and Dolby Vision is
   *     not present
   */
  public VideoDynamicRange {
    if (compatibleBase == CompatibleBase.NONE && !(dolbyVision instanceof DolbyVision.Present)) {
      throw new ContradictoryDynamicRangeException(
          "A dynamic range without Dolby Vision cannot lack a compatible base");
    }
  }

  /** The dynamic range of a stream whose probe reported none. */
  public static final VideoDynamicRange UNSPECIFIED =
      new VideoDynamicRange(
          CompatibleBase.UNSPECIFIED, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
}
