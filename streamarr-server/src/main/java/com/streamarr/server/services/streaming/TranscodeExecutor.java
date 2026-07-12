package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.transcode.engine.model.TranscodeRequest;
import java.util.UUID;

public interface TranscodeExecutor {

  TranscodeHandle start(TranscodeRequest request);

  void stop(UUID sessionId);

  /** Final process-level shutdown drain after per-session graceful stops have been attempted. */
  void forceStopAll();

  boolean isRunning(UUID sessionId);

  boolean isRunning(UUID sessionId, String variantLabel);

  boolean isHealthy();
}
