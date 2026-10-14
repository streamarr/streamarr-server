package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Clock;
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

  private final MediaFileContainerInfoRepository outcomes;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;
  private final ProbeSchedulingProperties properties;
  private final TaskRepository scanTasks;

  public CompletionHandler<KeyframePacketScanTaskRequest> handlerFor(
      KeyframePacketScanTaskRequest request, KeyframePacketScanResult result) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(
                _ -> {
                  var needed = outcomes.lockPacketScanInputs(request.mediaFileId());
                  if (needed.isEmpty()) {
                    operations.remove();
                    return;
                  }

                  var next =
                      request.toBuilder()
                          .snapshot(needed.get().snapshot())
                          .probeVersion(needed.get().probeVersion())
                          .build();
                  switch (result) {
                    case KeyframePacketScanResult.Completed _ when next.equals(request) ->
                        operations.remove();
                    case KeyframePacketScanResult.Completed _ ->
                        operations.reschedule(complete, clock.instant(), next);
                    case KeyframePacketScanResult.Deferred _ ->
                        scanTasks.reschedule(
                            complete.getExecution(),
                            RescheduleUpdate.toExecutionTime(
                                    clock.instant().plus(properties.busyWorkerRetryDelay()))
                                .data(next)
                                .build());
                  }
                });
  }
}
