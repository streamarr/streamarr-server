package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * What one connected worker can deliver beyond passing a copied video stream through (ADR 0036):
 * whether it checks a copy against its planned output, and the bitstream rewrites that it applies.
 */
@Builder
public record WorkerVideoCopySupport(
    boolean plannedOutput, @NonNull Set<BitstreamRewrite> bitstreamRewrites) {

  public WorkerVideoCopySupport {
    bitstreamRewrites = Set.copyOf(bitstreamRewrites);
  }

  /** Whether this worker delivers the planned copy as the plan describes it. */
  public boolean delivers(@NonNull PlannedVideoOutput.StreamCopy copy) {
    if (!copy.requiresPlannedOutputSupport()) {
      return true;
    }

    return plannedOutput && bitstreamRewrites.containsAll(copy.bitstreamRewrites());
  }
}
