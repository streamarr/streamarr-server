package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import java.time.Clock;
import java.time.Duration;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ends a keyframe packet scan execution under the media file lock. A probe publication schedules
 * its scan under the same lock, so a scan that a new outcome needs is never removed: the execution
 * runs again with the new outcome's snapshot and probe version. {@code scanTasks} completes
 * executions in the scan scheduler's own table.
 */
@Builder
@RequiredArgsConstructor
public class KeyframePacketScanCompletion {

  // A scan that timed out once may finish on a less busy worker. Three scans that ran without a
  // usable result show that the source cannot be scanned, so the file is encoded instead.
  private static final int MAX_FAILED_SCANS = 3;

  private final MediaFileContainerInfoRepository outcomes;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;
  private final ProbeSchedulingProperties properties;
  private final TaskRepository scanTasks;

  public CompletionHandler<KeyframePacketScanTaskRequest> handlerFor(
      KeyframePacketScanTaskRequest request, KeyframePacketScanResult result) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(_ -> finish(request, result, complete, operations));
  }

  private void finish(
      KeyframePacketScanTaskRequest request,
      KeyframePacketScanResult result,
      ExecutionComplete complete,
      ExecutionOperations<KeyframePacketScanTaskRequest> operations) {
    var needed = outcomes.lockPacketScanInputs(request.mediaFileId());
    if (needed.isEmpty()) {
      operations.remove();
      return;
    }

    var inputs = needed.get();
    if (!inputs.equals(request.inputs())) {
      operations.reschedule(complete, clock.instant(), withInputs(request, inputs));
      return;
    }

    switch (result) {
      case KeyframePacketScanResult.Completed _ -> operations.remove();
      case KeyframePacketScanResult.Deferred _ ->
          retryAfter(complete, request, properties.busyWorkerRetryDelay());
      case KeyframePacketScanResult.Failed _ when request.failedScans() + 1 >= MAX_FAILED_SCANS -> {
        markScansFailed(request);
        operations.remove();
      }
      case KeyframePacketScanResult.Failed _ ->
          retryAfter(
              complete,
              request.toBuilder().failedScans(request.failedScans() + 1).build(),
              CappedExponentialBackoff.delayAfter(request.failedScans()));
    }
  }

  private void retryAfter(
      ExecutionComplete complete, KeyframePacketScanTaskRequest next, Duration delay) {
    scanTasks.reschedule(
        complete.getExecution(),
        RescheduleUpdate.toExecutionTime(clock.instant().plus(delay)).data(next).build());
  }

  private void markScansFailed(KeyframePacketScanTaskRequest request) {
    outcomes.publishKeyframePacketScan(
        KeyframePacketScanPublication.builder()
            .mediaFileId(request.mediaFileId())
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .keyframes(
                new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.PACKET_SCAN_FAILED))
            .build());
  }

  private static KeyframePacketScanTaskRequest withInputs(
      KeyframePacketScanTaskRequest request, ProbeInputs inputs) {
    return request.toBuilder()
        .snapshot(inputs.snapshot())
        .probeVersion(inputs.probeVersion())
        .failedScans(0)
        .build();
  }
}
