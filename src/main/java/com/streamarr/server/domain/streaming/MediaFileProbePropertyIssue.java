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
   * The Dolby Vision configuration record pairs compatibility identifier 6, which only profile 7
   * defines, with another profile, such as profile 8. A player refuses a variant whose record
   * contradicts itself, so the stream is encoded until a copy can rewrite the record as profile 8.1
   * (streamarr/streamarr-server#396).
   */
  DOLBY_VISION_COMPATIBILITY_CONTRADICTS_PROFILE,
  /**
   * Every Dolby Vision RPU starts its sample, so each RPU sits either in the next picture's sample,
   * as a muxer that started a new sample at each RPU wrote it, or ahead of its own picture. A file
   * whose start was cut away gives this issue too. A copy would hand players the misplaced RPUs.
   */
  DOLBY_VISION_RPU_STARTS_SAMPLE;

  private static final int PROFILE_7_PQ_COMPATIBILITY_ID = 6;
  private static final int PROFILE_7 = 7;

  // The base format that each bl_signal_compatibility_id of the Dolby Vision profiles
  // specification claims. Identifier 6 is the PQ base of profile 7.
  private static final Map<Integer, CompatibleBase> CLAIMED_BASE_BY_COMPATIBILITY_ID =
      Map.of(
          0,
          CompatibleBase.NONE,
          1,
          CompatibleBase.PQ,
          2,
          CompatibleBase.SDR,
          4,
          CompatibleBase.HLG,
          PROFILE_7_PQ_COMPATIBILITY_ID,
          CompatibleBase.PQ);

  /** Whether the server must encode a stream that has this issue instead of copying it. */
  public boolean rulesOutCopy() {
    return switch (this) {
      case DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER,
          DOLBY_VISION_COMPATIBILITY_CONTRADICTS_PROFILE,
          DOLBY_VISION_RPU_STARTS_SAMPLE ->
          true;
    };
  }

  /** The issues of a video stream with this dynamic range. */
  public static Set<MediaFileProbePropertyIssue> foundIn(@NonNull VideoDynamicRange range) {
    var issues = EnumSet.noneOf(MediaFileProbePropertyIssue.class);
    if (!(range.dolbyVision()
        instanceof DolbyVision.Present(var declared, _, _, var rpuPlacement))) {
      return issues;
    }

    if (hasDisagreeingCompatibility(declared, range.compatibleBase())) {
      issues.add(DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER);
    }

    if (pairsProfile7CompatibilityWithAnotherProfile(declared)) {
      issues.add(DOLBY_VISION_COMPATIBILITY_CONTRADICTS_PROFILE);
    }

    if (rpuPlacement.startsSample()) {
      issues.add(DOLBY_VISION_RPU_STARTS_SAMPLE);
    }

    return issues;
  }

  private static boolean pairsProfile7CompatibilityWithAnotherProfile(
      Optional<DolbyVisionConfiguration> declared) {
    if (declared.isEmpty()) {
      return false;
    }

    var configuration = declared.get();
    var profile = configuration.profile();
    return configuration
            .baseLayerCompatibilityId()
            .equals(OptionalInt.of(PROFILE_7_PQ_COMPATIBILITY_ID))
        && profile.isPresent()
        && profile.getAsInt() != PROFILE_7;
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
