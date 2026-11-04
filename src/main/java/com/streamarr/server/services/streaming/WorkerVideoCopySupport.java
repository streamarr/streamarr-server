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

  /**
   * Whether this worker checks a copy against its planned output and applies every bitstream
   * rewrite that the copy lists.
   */
  public boolean deliversPlannedOutputOf(@NonNull PlannedVideoOutput.StreamCopy copy) {
    return plannedOutput && bitstreamRewrites.containsAll(copy.bitstreamRewrites());
  }
}
