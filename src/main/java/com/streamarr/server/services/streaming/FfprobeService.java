package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;

public interface FfprobeService {

  /** Returns a complete success or terminal media error. Execution failures remain retryable. */
  ProbeOutcome probe(ProbeExecutionRequest request);
}
