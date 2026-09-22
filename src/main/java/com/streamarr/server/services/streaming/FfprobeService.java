package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;

public interface FfprobeService {

  /**
   * Returns a complete success or terminal media error. Execution failures remain retryable.
   *
   * @throws com.streamarr.server.exceptions.ProbeWorkersBusyException when every compatible
   *     worker's slots are in use, so the probe did not start
   */
  ProbeOutcome probe(ProbeExecutionRequest request);
}
