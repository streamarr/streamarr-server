package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * What a packet scan learned about a source's keyframes and about where its streams end, with the
 * source snapshot and probe version of the stored outcome that asked for the scan. {@code
 * streamEnds} is empty when the scan read no stream's end.
 */
@Builder(toBuilder = true)
public record KeyframePacketScanPublication(
    @NonNull UUID mediaFileId,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion,
    @NonNull KeyframeKnowledge keyframes,
    @NonNull List<SourceStreamEnd> streamEnds) {

  public KeyframePacketScanPublication {
    streamEnds = List.copyOf(streamEnds);
    switch (keyframes) {
      case KeyframeKnowledge.Known _, KeyframeKnowledge.Unavailable _ -> {
        // A packet scan reports one of these.
      }
      case KeyframeKnowledge.PacketScanRequired _, KeyframeKnowledge.NotReported _ ->
          throw new IllegalArgumentException("A packet scan stores known or unavailable keyframes");
    }
  }

  // Lombok builder default: its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class KeyframePacketScanPublicationBuilder {
    private List<SourceStreamEnd> streamEnds = List.of();
  }
}
