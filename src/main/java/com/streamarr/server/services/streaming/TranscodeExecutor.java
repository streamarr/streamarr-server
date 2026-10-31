package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TranscodeExecutor {

  /** Sentinel returned by {@link #availableSlots()} when the executor imposes no slot limit. */
  int UNBOUNDED_SLOTS = Integer.MAX_VALUE;

  /**
   * Starts on the first execution target that can run the request. A request with a pinned encoder
   * starts only on a target that advertises the same worker-verified encoder: the same encoder
   * backend with the same keyframe-verified flag. The handle carries the worker-verified encoder
   * that the accepting target advertises for an encode.
   */
  TranscodeHandle start(TranscodeRequest request);

  /**
   * Starts on one specific execution target. Throws {@code TranscodeException} when the target
   * refuses or cannot start the producer, or when the request has a pinned encoder that the target
   * does not advertise. The handle carries the worker-verified encoder that the target advertises
   * for an encode.
   */
  TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target);

  void stop(UUID sessionId);

  /** Stops the stream's producers and discards retained outcomes when its session is destroyed. */
  void destroySession(UUID sessionId);

  void stopVariant(UUID sessionId, String variantLabel);

  boolean isRunning(UUID sessionId, String variantLabel);

  /**
   * The execution target that runs the variant's job attempt. Empty when no job attempt of the
   * variant runs.
   */
  Optional<ExecutionTargetId> runningExecutionTarget(UUID sessionId, String variantLabel);

  /**
   * Remains available until the stream session is destroyed, including before its handle is
   * installed.
   */
  Optional<TranscodeFailure> terminalFailure(UUID sessionId);

  boolean isHealthy();

  int availableSlots();

  /**
   * Free slots on the workers that can run a producer with this decision: any worker for a copy,
   * and for an encode only a worker that can encode the decision's video codec family.
   */
  int availableSlots(TranscodeDecision decision);

  /**
   * Free slots on the workers that can run a later job attempt of a variant pinned to this
   * worker-verified encoder: only workers that advertise the same encoder backend with the same
   * keyframe-verified flag for its codec family.
   */
  int availableSlots(WorkerVerifiedEncoder pinnedEncoder);

  /**
   * Identities eligible to run a producer with this decision right now: any worker for a copy, and
   * for an encode only a worker that can encode the decision's video codec family. Iteration order
   * drives failover order.
   */
  Set<ExecutionTargetId> executionTargets(TranscodeDecision decision);

  /**
   * Identities eligible to run a later job attempt of a variant pinned to this worker-verified
   * encoder: only workers that advertise the same encoder backend with the same keyframe-verified
   * flag for its codec family. Iteration order drives failover order.
   */
  Set<ExecutionTargetId> executionTargets(WorkerVerifiedEncoder pinnedEncoder);

  /**
   * The video codec families, such as {@code h264} or {@code av1}, that a connected worker with a
   * free slot can encode with a worker-verified encoder. An encode of any other family has no
   * execution target now.
   */
  Set<String> encodableVideoCodecFamilies();

  /**
   * The video codec families that a connected worker with a free slot can encode with a
   * worker-verified hardware encoder.
   */
  Set<String> hardwareEncodableVideoCodecFamilies();

  /**
   * The codec families of connected, source-accessible worker-verified encoders, including busy
   * workers.
   */
  Set<String> verifiedVideoCodecFamilies();

  /**
   * The codec families of connected, source-accessible worker-verified hardware encoders, including
   * busy workers.
   */
  Set<String> verifiedHardwareVideoCodecFamilies();
}
