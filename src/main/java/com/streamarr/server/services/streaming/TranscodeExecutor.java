package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import java.util.UUID;

public interface TranscodeExecutor {

  /** Sentinel returned by {@link #availableSlots()} when the executor imposes no slot limit. */
  int UNBOUNDED_SLOTS = Integer.MAX_VALUE;

  TranscodeHandle start(TranscodeRequest request);

  void stop(UUID sessionId);

  boolean isRunning(UUID sessionId);

  boolean isRunning(UUID sessionId, String variantLabel);

  boolean isHealthy();

  int availableSlots();
}
