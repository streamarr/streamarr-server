package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.MediaFileProbePropertyIssue;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import java.util.Optional;
import java.util.Set;

/**
 * The copy rule that still holds for what the playlist cannot describe yet (ADR 0013,
 * streamarr/streamarr-server#396). A PQ or HLG source that probe version 5 or later described may
 * be copied, with or without Dolby Vision, and its capability entry decides whether the client
 * accepts it. Dolby Vision without a compatible base, and any HDR base of an earlier probe version,
 * is refused. A source that the rule refuses is encoded to 8-bit SDR.
 */
final class InterimVideoCopyRule {

  // A playlist describes a copy only under these sample entries. The worker writes a Dolby Vision
  // copy with an SDR base under its base layer's entry, and never copies under hev1.
  private static final Set<String> DESCRIBABLE_SAMPLE_ENTRIES = Set.of("avc1", "hvc1", "av01");

  private InterimVideoCopyRule() {}

  /** The first reason that this rule refuses a copy for, or empty when it permits the copy. */
  static Optional<DecisionReason> copyRefusal(String videoCodec, VideoPlaybackFacts facts) {
    var dynamicRangeRefusal = dynamicRangeRefusal(facts);
    if (dynamicRangeRefusal.isPresent()) {
      return dynamicRangeRefusal;
    }

    var issue =
        MediaFileProbePropertyIssue.foundIn(facts.dynamicRange()).stream()
            .filter(MediaFileProbePropertyIssue::rulesOutCopy)
            .findFirst();
    if (issue.isPresent()) {
      return Optional.of(reasonFor(issue.get()));
    }

    var bitDepthAndChromaRefusal = bitDepthAndChromaRefusal(videoCodec, facts);
    if (bitDepthAndChromaRefusal.isPresent()) {
      return bitDepthAndChromaRefusal;
    }

    if (facts.codecString().isEmpty()) {
      return Optional.of(DecisionReason.VIDEO_CODEC_STRING_UNKNOWN);
    }

    if (!hasDescribableCodecString(facts.codecString())) {
      return Optional.of(DecisionReason.SAMPLE_ENTRY_NOT_COPYABLE);
    }

    if (lacksCodecProfile(facts)) {
      return Optional.of(DecisionReason.VIDEO_CODEC_PROFILE_UNKNOWN);
    }

    return Optional.empty();
  }

  // From probe version 5 on, a codec profile that the probe could not name rules out a copy. An
  // earlier probe version reports no codec profile, and its copies stay as they were.
  private static boolean lacksCodecProfile(VideoPlaybackFacts facts) {
    return facts
        .videoProperties()
        .map(properties -> properties.codecProfile().isEmpty())
        .orElse(false);
  }

  // A probe version 4 outcome reports no codec profile for an entry to check, so it keeps that
  // version's decision until the file's probe version 5 outcome is stored (ADR 0032).
  private static Optional<DecisionReason> dynamicRangeRefusal(VideoPlaybackFacts facts) {
    return switch (facts.dynamicRange().compatibleBase()) {
      case SDR, UNSPECIFIED -> Optional.empty();
      case PQ, HLG -> hdrBaseRefusal(facts);
      case NONE -> Optional.of(DecisionReason.DOLBY_VISION_WITHOUT_COMPATIBLE_BASE);
    };
  }

  private static Optional<DecisionReason> hdrBaseRefusal(VideoPlaybackFacts facts) {
    if (facts.videoProperties().isPresent()) {
      return Optional.empty();
    }

    return Optional.of(DecisionReason.VIDEO_PROPERTIES_NOT_PROBED);
  }

  /** The reason that names the property issue. */
  static DecisionReason reasonFor(MediaFileProbePropertyIssue issue) {
    return switch (issue) {
      case DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER ->
          DecisionReason.DOLBY_VISION_COMPATIBILITY_DISAGREES_WITH_TRANSFER;
      case DOLBY_VISION_RPU_STARTS_SAMPLE -> DecisionReason.DOLBY_VISION_RPU_STARTS_SAMPLE;
    };
  }

  // Clients that declare nothing play only 8-bit 4:2:0 H.264 (streamarr/streamarr-server#395). A
  // depth or subsampling that the probe did not report is unknown, not another format.
  private static Optional<DecisionReason> bitDepthAndChromaRefusal(
      String videoCodec, VideoPlaybackFacts facts) {
    if (!"h264".equals(videoCodec)) {
      return Optional.empty();
    }

    if (facts.bitDepth().isEmpty()) {
      return Optional.of(DecisionReason.VIDEO_BIT_DEPTH_UNKNOWN);
    }

    if (facts.chromaSubsampling().isEmpty()) {
      return Optional.of(DecisionReason.VIDEO_CHROMA_SUBSAMPLING_UNKNOWN);
    }

    if (facts.bitDepth().getAsInt() != 8
        || facts.chromaSubsampling().get() != ChromaSubsampling.CHROMA_420) {
      return Optional.of(DecisionReason.H264_NOT_8_BIT_4_2_0);
    }

    return Optional.empty();
  }

  private static boolean hasDescribableCodecString(Optional<String> codecString) {
    return codecString
        .map(string -> string.split("\\.", 2)[0])
        .filter(DESCRIBABLE_SAMPLE_ENTRIES::contains)
        .isPresent();
  }
}
