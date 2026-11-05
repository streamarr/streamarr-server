package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * What one connected worker can deliver beyond passing a copied video stream through (ADR 0036):
 * whether it checks a copy against its planned output, the bitstream rewrites that it applies, and
 * the Dolby Vision sample entries that it writes in place of a codec's base entry (ADR 0013).
 */
@Builder
public record WorkerVideoCopySupport(
    boolean plannedOutput,
    @NonNull Set<BitstreamRewrite> bitstreamRewrites,
    @NonNull Set<VideoSampleEntry> dolbyVisionSampleEntries) {

  public WorkerVideoCopySupport {
    bitstreamRewrites = Set.copyOf(bitstreamRewrites);
    dolbyVisionSampleEntries = Set.copyOf(dolbyVisionSampleEntries);
  }

  /**
   * Whether this worker checks a copy against its planned output, applies every bitstream rewrite
   * that the copy lists, and writes the Dolby Vision sample entry that a profile 5 or 10.0 copy
   * carries.
   */
  public boolean deliversPlannedOutputOf(@NonNull PlannedVideoOutput.StreamCopy copy) {
    return plannedOutput
        && bitstreamRewrites.containsAll(copy.bitstreamRewrites())
        && copy.dolbyVisionOutput().flatMap(DolbyVisionOutput::dolbyVisionSampleEntry).stream()
            .allMatch(dolbyVisionSampleEntries::contains);
  }

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class WorkerVideoCopySupportBuilder {
    private Set<VideoSampleEntry> dolbyVisionSampleEntries = Set.of();
  }
}
