package com.streamarr.server.domain.streaming;

import java.util.Map;
import java.util.OptionalInt;
import lombok.NonNull;

/** The dynamic range of one video stream, as ADR 0036 defines it. */
public record VideoDynamicRange(
    @NonNull CompatibleBase compatibleBase,
    @NonNull Presence hdr10Plus,
    @NonNull DolbyVision dolbyVision) {

  /** The dynamic range of a stream whose probe reported none. */
  public static final VideoDynamicRange UNSPECIFIED =
      new VideoDynamicRange(
          CompatibleBase.UNSPECIFIED, Presence.UNSPECIFIED, new DolbyVision.Unspecified());

  // The base format that each bl_signal_compatibility_id of the Dolby Vision profiles
  // specification claims. Identifier 6 is the PQ base of profile 7.
  private static final Map<Integer, CompatibleBase> CLAIMED_BASE_BY_COMPATIBILITY_ID =
      Map.of(
          0, CompatibleBase.NONE,
          1, CompatibleBase.PQ,
          2, CompatibleBase.SDR,
          4, CompatibleBase.HLG,
          6, CompatibleBase.PQ);

  /**
   * Whether the Dolby Vision configuration record claims a compatibility identifier whose base
   * differs from the compatible base that the signaled transfer decided. An identifier without a
   * known base disagrees with every compatible base.
   */
  public boolean hasDisagreeingDolbyVisionCompatibility() {
    if (!(dolbyVision instanceof DolbyVision.Present(var declared, _))) {
      return false;
    }

    var compatibilityId =
        declared
            .map(DolbyVisionConfiguration::baseLayerCompatibilityId)
            .orElse(OptionalInt.empty());
    if (compatibilityId.isEmpty()) {
      return false;
    }

    return CLAIMED_BASE_BY_COMPATIBILITY_ID.get(compatibilityId.getAsInt()) != compatibleBase;
  }
}
