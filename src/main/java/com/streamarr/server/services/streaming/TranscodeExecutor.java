package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import java.util.UUID;

public interface TranscodeExecutor {

  TranscodeHandle start(TranscodeRequest request);

  void stop(UUID sessionId);

  boolean isRunning(UUID sessionId);

  boolean isRunning(UUID sessionId, String variantLabel);

  boolean isHealthy();
}
