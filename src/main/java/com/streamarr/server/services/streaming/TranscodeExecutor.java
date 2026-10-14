package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import java.util.Set;
import java.util.UUID;

public interface TranscodeExecutor {

  /** Sentinel returned by {@link #availableSlots()} when the executor imposes no slot limit. */
  int UNBOUNDED_SLOTS = Integer.MAX_VALUE;

  /**
   * Starts on the first execution target that can run the request. A request with a pinned encoder
   * backend starts only on a target that advertises that backend. The handle carries the
   * worker-verified encoder that the accepting target advertises for an encode.
   */
  TranscodeHandle start(TranscodeRequest request);

  /**
   * Starts on one specific execution target. Throws {@code TranscodeException} when the target
   * refuses or cannot start the producer. The handle carries the worker-verified encoder that the
   * target advertises for an encode.
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

  /**
   * Identities eligible to run a producer with this decision right now: any worker for a copy, and
   * for an encode only a worker that can encode the decision's video codec family. Iteration order
   * drives failover order.
   */
  Set<ExecutionTargetId> executionTargets(TranscodeDecision decision);

  /**
   * Identities eligible to run a later job attempt of a variant pinned to this encoder backend:
   * only workers that advertise the same backend for its codec family. Iteration order drives
   * failover order.
   */
  Set<ExecutionTargetId> executionTargets(EncoderBackend pinnedEncoderBackend);

  /**
   * The video codec families, such as {@code h264} or {@code av1}, that a connected worker with a
   * free slot can encode with a worker-verified encoder. An encode of any other family has no
   * execution target now.
   */
  Set<String> encodableVideoCodecFamilies();
}
