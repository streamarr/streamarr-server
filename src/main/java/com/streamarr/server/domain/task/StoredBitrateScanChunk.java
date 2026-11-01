package com.streamarr.server.domain.task;

import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * A finished bitrate scan chunk: {@code keyframeIntervalCount} keyframe intervals from {@code
 * firstKeyframeInterval}, with every measured stream in stream index order.
 */
@Builder
public record StoredBitrateScanChunk(
    int firstKeyframeInterval,
    int keyframeIntervalCount,
    @NonNull List<MeasuredChunkStream> streams) {

  public StoredBitrateScanChunk {
    streams = List.copyOf(streams);
  }
}
