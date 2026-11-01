package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.streaming.StreamMeasurementUnavailableReason;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.domain.task.StreamMeasurementPublication;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ends a bitrate scan execution under the media file lock (ADR 0054). A probe publication requests
 * its scan under the same lock, so a scan that new inputs need is never removed: the execution runs
 * again with the new inputs. Between chunks the scan goes back to the scan table at once, so a
 * keyframe packet scan that waits there runs before the next chunk. {@code scanTasks} completes
 * executions in the scan scheduler's own table and keeps their priority.
 */
@Builder
@RequiredArgsConstructor
public class BitrateScanCompletion {

  static final String FINISHED_SCANS_METRIC = "streamarr.probes.bitrate_scans_finished";

  // Three attempts of one chunk without a result, or three scans whose chunks cannot be joined,
  // show
  // that the file cannot be measured, so the copy keeps its unmeasured cost.
  private static final int MAX_FAILED_ATTEMPTS = 3;

  private final BitrateScanRepository scans;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;
  private final ProbeSchedulingProperties properties;
  private final TaskRepository scanTasks;
  private final MeterRegistry meterRegistry;

  public CompletionHandler<BitrateScanTaskRequest> handlerFor(
      BitrateScanTaskRequest request, BitrateScanResult result) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(_ -> finish(request, result, complete, operations));
  }

  private void finish(
      BitrateScanTaskRequest request,
      BitrateScanResult result,
      ExecutionComplete complete,
      ExecutionOperations<BitrateScanTaskRequest> operations) {
    var needed = scans.lockBitrateScanInputs(request.mediaFileId());
    if (needed.isEmpty()) {
      scans.deleteChunks(request.mediaFileId());
      operations.remove();
      return;
    }

    var inputs = needed.get();
    if (!inputs.key().equals(request.key())) {
      retryAfter(complete, withInputs(request, inputs), Duration.ZERO);
      return;
    }

    switch (result) {
      case BitrateScanResult.Completed _ -> retryAfter(complete, request, Duration.ZERO);
      case BitrateScanResult.SourceChanged _ -> {
        scans.deleteChunks(request.mediaFileId());
        operations.remove();
      }
      case BitrateScanResult.ChunkStored _, BitrateScanResult.AllChunksStored _ ->
          publishOrContinue(request, inputs, complete, operations);
      case BitrateScanResult.Deferred _ ->
          retryAfter(complete, request, properties.busyWorkerRetryDelay());
      case BitrateScanResult.Failed(var firstKeyframeInterval) ->
          countFailedChunkAttempt(request, firstKeyframeInterval, complete, operations);
      case BitrateScanResult.CopyChangesPackets _ ->
          markUnavailable(
              request, StreamMeasurementUnavailableReason.COPY_CHANGES_PACKETS, operations);
    }
  }

  private void publishOrContinue(
      BitrateScanTaskRequest request,
      BitrateScanInputs inputs,
      ExecutionComplete complete,
      ExecutionOperations<BitrateScanTaskRequest> operations) {
    var chunks = scans.findChunks(request.mediaFileId(), request.key());
    var keyframeCount = inputs.positions().keyframes().size();
    if (BitrateScanChunks.coveredIntervals(chunks) < keyframeCount) {
      retryAfter(complete, request, Duration.ZERO);
      return;
    }

    var measurements = BitrateScanAssembly.assemble(chunks, keyframeCount);
    scans.deleteChunks(request.mediaFileId());
    if (measurements.isEmpty()) {
      meterRegistry
          .counter(BitrateScanExecution.CHUNK_FAILURES_METRIC, "reason", "assembly")
          .increment();
      countFailedAssembly(request, complete, operations);
      return;
    }

    scans.publishMeasurement(
        StreamMeasurementPublication.builder()
            .mediaFileId(request.mediaFileId())
            .key(request.key())
            .measurements(measurements.get())
            .build());
    countFinished("measured");
    operations.remove();
  }

  // The failed attempts belong to the chunk that starts at their keyframe interval, so a chunk that
  // was stored before its execution completed leaves the next chunk a full set of attempts.
  private void countFailedChunkAttempt(
      BitrateScanTaskRequest request,
      int firstKeyframeInterval,
      ExecutionComplete complete,
      ExecutionOperations<BitrateScanTaskRequest> operations) {
    var attempts = 1;
    if (request.failedAttempts() > 0 && request.failedKeyframeInterval() == firstKeyframeInterval) {
      attempts = request.failedAttempts() + 1;
    }

    if (attempts >= MAX_FAILED_ATTEMPTS) {
      markUnavailable(request, StreamMeasurementUnavailableReason.FAILED_ATTEMPTS, operations);
      return;
    }

    retryAfter(
        complete,
        request.toBuilder()
            .failedAttempts(attempts)
            .failedKeyframeInterval(firstKeyframeInterval)
            .build(),
        CappedExponentialBackoff.delayAfter(attempts - 1));
  }

  private void countFailedAssembly(
      BitrateScanTaskRequest request,
      ExecutionComplete complete,
      ExecutionOperations<BitrateScanTaskRequest> operations) {
    var assemblies = request.failedAssemblies() + 1;
    if (assemblies >= MAX_FAILED_ATTEMPTS) {
      markUnavailable(request, StreamMeasurementUnavailableReason.FAILED_ATTEMPTS, operations);
      return;
    }

    retryAfter(
        complete,
        request.toBuilder().failedAssemblies(assemblies).build(),
        CappedExponentialBackoff.delayAfter(assemblies - 1));
  }

  private void markUnavailable(
      BitrateScanTaskRequest request,
      StreamMeasurementUnavailableReason reason,
      ExecutionOperations<BitrateScanTaskRequest> operations) {
    scans.markMeasurementUnavailable(request.mediaFileId(), request.key(), reason);
    scans.deleteChunks(request.mediaFileId());
    countFinished(reason.name().toLowerCase(Locale.ROOT));
    operations.remove();
  }

  private void countFinished(String outcome) {
    meterRegistry.counter(FINISHED_SCANS_METRIC, "outcome", outcome).increment();
  }

  private void retryAfter(ExecutionComplete complete, BitrateScanTaskRequest next, Duration delay) {
    scanTasks.reschedule(
        complete.getExecution(),
        RescheduleUpdate.toExecutionTime(clock.instant().plus(delay)).data(next).build());
  }

  private static BitrateScanTaskRequest withInputs(
      BitrateScanTaskRequest request, BitrateScanInputs inputs) {
    return request.toBuilder()
        .snapshot(inputs.key().snapshot())
        .keyframePositionsDigest(inputs.key().keyframePositionsDigest())
        .measurementVersion(inputs.key().measurementVersion())
        .failedAttempts(0)
        .failedKeyframeInterval(0)
        .failedAssemblies(0)
        .build();
  }
}
