package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/** What a worker found in one bitrate scan chunk (ADR 0054). */
public sealed interface BitrateScanChunkOutcome {

  /** The worker measured every requested stream, each once. */
  record Measured(@NonNull List<MeasuredChunkStream> streams) implements BitrateScanChunkOutcome {
    public Measured {
      streams = List.copyOf(streams);
    }
  }

  /** A stream copy would change the size or the number of the stream's packets. */
  record CopyChangesPackets(int streamIndex) implements BitrateScanChunkOutcome {}

  /** The worker could not read the chunk's packets to the chunk's end. */
  record MediaError(@NonNull ProbeError error) implements BitrateScanChunkOutcome {}
}
