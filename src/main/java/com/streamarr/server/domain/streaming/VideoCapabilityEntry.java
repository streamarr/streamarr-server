package com.streamarr.server.domain.streaming;

import java.util.EnumSet;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * One video capability entry (ADR 0049): one group of values and maximums that the player accepts
 * together for one codec family. The player accepts every combination that takes one value from
 * each list and stays within every maximum. {@code compatibleBases} names only SDR, PQ or HLG.
 * {@code hls} tells whether the entry applies to HLS delivery.
 */
@Builder
public record VideoCapabilityEntry(
    @NonNull ProbedCodecFamily codecFamily,
    @NonNull List<VideoCodecProfileCapability> codecProfiles,
    int maxBitDepth,
    int maxWidth,
    int maxHeight,
    double maxFrameRate,
    @NonNull List<CompatibleBase> compatibleBases,
    @NonNull List<DolbyVisionProfileCapability> dolbyVisionProfiles,
    boolean hdr10Plus,
    boolean hls) {

  private static final EnumSet<CompatibleBase> DECLARABLE_COMPATIBLE_BASES =
      EnumSet.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);

  public VideoCapabilityEntry {
    if (codecFamily == ProbedCodecFamily.OTHER) {
      throw new IllegalArgumentException("A video capability entry names a known codec family");
    }

    if (!DECLARABLE_COMPATIBLE_BASES.containsAll(compatibleBases)) {
      throw new IllegalArgumentException(
          "A video capability entry declares only SDR, PQ or HLG compatible bases");
    }

    codecProfiles = List.copyOf(codecProfiles);
    compatibleBases = List.copyOf(compatibleBases);
    dolbyVisionProfiles = List.copyOf(dolbyVisionProfiles);
  }

  /** Whether this entry accepts the stream copy over HLS. */
  public boolean acceptsHlsCopy(@NonNull VideoStreamCopy copy) {
    return hls && copy.codecFamily() == codecFamily;
  }
}
