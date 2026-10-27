package com.streamarr.server.domain.streaming;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import lombok.NonNull;

/**
 * A problem in the properties that a media-file probe reported for a video stream (ADR 0036). The
 * server derives the issues from the stored facts whenever it decides, and never stores them, so a
 * change to this rule needs no new probe version.
 */
public enum MediaFileProbePropertyIssue {
  /**
   * The Dolby Vision configuration record claims a compatibility identifier whose base differs from
   * the compatible base that the signaled transfer decided, such as profile 7 tagged as SDR. An
   * identifier without a known base disagrees with every compatible base. The stream is encoded,
   * interpreting the signaled transfer.
   */
  DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER,
  /**
   * A sample starts with a Dolby Vision RPU ahead of its picture: a muxer started a new sample at
   * each RPU, so each RPU sits in the next picture's sample and the last sample holds no picture.
   * Players fail at the end of a copy of such a stream.
   */
  DOLBY_VISION_RPU_STARTS_SAMPLE;

  // The base format that each bl_signal_compatibility_id of the Dolby Vision profiles
  // specification claims. Identifier 6 is the PQ base of profile 7.
  private static final Map<Integer, CompatibleBase> CLAIMED_BASE_BY_COMPATIBILITY_ID =
      Map.of(
          0, CompatibleBase.NONE,
          1, CompatibleBase.PQ,
          2, CompatibleBase.SDR,
          4, CompatibleBase.HLG,
          6, CompatibleBase.PQ);

  /** Whether the server must encode a stream that has this issue instead of copying it. */
  public boolean rulesOutCopy() {
    return switch (this) {
      case DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER, DOLBY_VISION_RPU_STARTS_SAMPLE ->
          true;
    };
  }

  /** The issues of a video stream with this dynamic range. */
  public static Set<MediaFileProbePropertyIssue> foundIn(@NonNull VideoDynamicRange range) {
    var issues = EnumSet.noneOf(MediaFileProbePropertyIssue.class);
    if (!(range.dolbyVision()
        instanceof DolbyVision.Present(var declared, _, _, var rpuStartsSample))) {
      return issues;
    }

    if (hasDisagreeingCompatibility(declared, range.compatibleBase())) {
      issues.add(DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER);
    }

    if (rpuStartsSample == Presence.PRESENT) {
      issues.add(DOLBY_VISION_RPU_STARTS_SAMPLE);
    }

    return issues;
  }

  private static boolean hasDisagreeingCompatibility(
      Optional<DolbyVisionConfiguration> declared, CompatibleBase signaled) {
    var compatibilityId =
        declared
            .map(DolbyVisionConfiguration::baseLayerCompatibilityId)
            .orElse(OptionalInt.empty());
    if (compatibilityId.isEmpty()) {
      return false;
    }

    return CLAIMED_BASE_BY_COMPATIBILITY_ID.get(compatibilityId.getAsInt()) != signaled;
  }
}
