package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * What a packet scan learned about a source's keyframes, with the source snapshot and probe version
 * of the stored outcome that asked for the scan.
 */
@Builder(toBuilder = true)
public record KeyframePacketScanPublication(
    @NonNull UUID mediaFileId,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion,
    @NonNull KeyframeKnowledge keyframes) {

  public KeyframePacketScanPublication {
    switch (keyframes) {
      case KeyframeKnowledge.Known _, KeyframeKnowledge.Unavailable _ -> {
        // A packet scan reports one of these.
      }
      case KeyframeKnowledge.PacketScanRequired _, KeyframeKnowledge.NotReported _ ->
          throw new IllegalArgumentException("A packet scan stores known or unavailable keyframes");
    }
  }
}
