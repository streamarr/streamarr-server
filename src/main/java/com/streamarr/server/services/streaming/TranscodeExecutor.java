package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import java.util.Set;
import java.util.UUID;

public interface TranscodeExecutor {

  /** Sentinel returned by {@link #availableSlots()} when the executor imposes no slot limit. */
  int UNBOUNDED_SLOTS = Integer.MAX_VALUE;

  TranscodeHandle start(TranscodeRequest request);

  /**
   * Starts on one specific execution target. Throws {@code TranscodeException} when the target
   * refuses or cannot start the producer.
   */
  TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target);

  void stop(UUID sessionId);

  void stopVariant(UUID sessionId, String variantLabel);

  boolean isRunning(UUID sessionId);

  boolean isRunning(UUID sessionId, String variantLabel);

  boolean isHealthy();

  int availableSlots();

  /** Identities eligible to run a producer right now; iteration order drives failover order. */
  Set<ExecutionTargetId> executionTargets();
}
