package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.BitrateScanChunkOutcome;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;

@FunctionalInterface
public interface BitrateScanner {

  /**
   * Measures every requested stream's packets in each keyframe interval of one bitrate scan chunk
   * (ADR 0054).
   *
   * @throws com.streamarr.server.exceptions.ProbeWorkersBusyException when no connected worker that
   *     runs the chunk's measurement version can start the chunk now, so the chunk did not start
   * @throws com.streamarr.server.exceptions.ProbeTimedOutException when the chunk reached the
   *     bitrate scan chunk timeout
   * @throws com.streamarr.server.exceptions.ProbeContractViolationException when the worker's reply
   *     breaks the worker contract
   * @throws com.streamarr.server.exceptions.ProbeExecutionException when the chunk may succeed on a
   *     retry, for example after its worker disconnected
   */
  BitrateScanChunkOutcome scanChunk(BitrateScanChunkRequest request);

  /**
   * The most keyframe intervals, summed over a chunk's measured streams, that the scanner takes
   * back from one chunk. A chunk that asks for more cannot succeed. A scanner that states no limit
   * takes any chunk.
   */
  default int measuredKeyframeIntervalLimit() {
    return Integer.MAX_VALUE;
  }
}
