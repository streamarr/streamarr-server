package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeDecision;
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

  boolean isRunning(UUID sessionId, String variantLabel);

  boolean isHealthy();

  int availableSlots();

  /**
   * Free slots on the workers that can run a producer with this decision: any worker for a copy,
   * and for an encode only a worker that can encode the decision's video codec family.
   */
  int availableSlots(TranscodeDecision decision);

  /** Identities eligible to run a producer right now; iteration order drives failover order. */
  Set<ExecutionTargetId> executionTargets();

  /**
   * The video codec families, such as {@code h264} or {@code av1}, that a connected worker with a
   * free slot can encode with a worker-verified encoder. An encode of any other family has no
   * execution target now.
   */
  Set<String> encodableVideoCodecFamilies();
}
