package com.streamarr.server.domain.streaming;

import com.streamarr.server.exceptions.ContradictoryDynamicRangeException;
import java.util.Collection;
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

  /**
   * The range that a stream copy of this range delivers after the bitstream rewrites (ADR 0036).
   * Stripping Dolby Vision from a range without a compatible base throws, because the server never
   * strips profiles 5 and 10.0.
   */
  public VideoDynamicRange afterRewrites(@NonNull Collection<BitstreamRewrite> rewrites) {
    var hdr10PlusAfter = hdr10Plus;
    if (rewrites.contains(BitstreamRewrite.STRIP_HDR10_PLUS)) {
      hdr10PlusAfter = Presence.ABSENT;
    }

    var dolbyVisionAfter = dolbyVision;
    if (rewrites.contains(BitstreamRewrite.STRIP_DOLBY_VISION)) {
      dolbyVisionAfter = new DolbyVision.Absent();
    }

    return new VideoDynamicRange(compatibleBase, hdr10PlusAfter, dolbyVisionAfter);
  }

  /** SDR without HDR10+ or Dolby Vision. */
  public static final VideoDynamicRange SDR =
      new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent());

  /** The dynamic range of a stream whose probe reported none. */
  public static final VideoDynamicRange UNSPECIFIED =
      new VideoDynamicRange(
          CompatibleBase.UNSPECIFIED, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
}
