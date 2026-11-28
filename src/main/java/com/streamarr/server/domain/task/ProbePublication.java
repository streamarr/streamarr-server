package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A probe result together with the source snapshot and probe version it was produced for. The
 * repository writes it only while no newer result exists for the same snapshot. Its outcome never
 * names positions kept from an earlier packet scan: only the repository keeps those.
 */
@Builder
public record ProbePublication(
    @NonNull UUID mediaFileId,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion,
    @NonNull ProbeOutcome outcome) {

  public ProbePublication {
    if (outcome instanceof ProbeOutcome.Success success
        && success.keyframes() instanceof KeyframeKnowledge.PacketScanRequiredWithKeptPositions) {
      throw new IllegalArgumentException(
          "A probe reports no positions kept from an earlier packet scan");
    }
  }
}
