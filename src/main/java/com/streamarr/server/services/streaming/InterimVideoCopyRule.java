package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.MediaFileProbePropertyIssue;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import java.util.Optional;
import java.util.OptionalInt;
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

  static boolean permitsCopy(String videoCodec, VideoPlaybackFacts facts) {
    var range = facts.dynamicRange();
    return isCopyableDynamicRange(facts)
        && MediaFileProbePropertyIssue.foundIn(range).stream()
            .noneMatch(MediaFileProbePropertyIssue::rulesOutCopy)
        && isCopyableBitDepthAndChroma(videoCodec, facts)
        && hasDescribableCodecString(facts.codecString())
        && !lacksCodecProfile(facts);
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
  private static boolean isCopyableDynamicRange(VideoPlaybackFacts facts) {
    return switch (facts.dynamicRange().compatibleBase()) {
      case SDR, UNSPECIFIED -> true;
      case PQ, HLG -> facts.videoProperties().isPresent();
      case NONE -> false;
    };
  }

  // Clients that declare nothing play only 8-bit 4:2:0 H.264 (streamarr/streamarr-server#395).
  private static boolean isCopyableBitDepthAndChroma(String videoCodec, VideoPlaybackFacts facts) {
    if (!"h264".equals(videoCodec)) {
      return true;
    }

    return facts.bitDepth().equals(OptionalInt.of(8))
        && facts.chromaSubsampling().equals(Optional.of(ChromaSubsampling.CHROMA_420));
  }

  private static boolean hasDescribableCodecString(Optional<String> codecString) {
    return codecString
        .map(string -> string.split("\\.", 2)[0])
        .filter(DESCRIBABLE_SAMPLE_ENTRIES::contains)
        .isPresent();
  }
}
