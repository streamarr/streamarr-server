package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single entry point for requesting a probe. Requests are idempotent per media file: a pending
 * instance keeps its inputs unless the request carries new ones, and new inputs never move a
 * pending instance earlier. Desired inputs remain durable while an execution is running and are
 * checked again when it completes.
 */
@Service
@RequiredArgsConstructor
public class SchedulerProbeTaskRequests implements ProbeTaskRequests {

  private final SchedulerClient client;

  private final Task<ProbeTaskRequest> task;
  private final MediaFileContainerInfoRepository outcomes;
  private final Clock clock;

  @Override
  @Transactional
  public void request(ProbeTaskRequest request) {
    enqueue(request);
  }

  @Override
  @Transactional
  public void requestRetryingFailure(ProbeTaskRequest request) {
    // Read before enqueue, which clears the saved failure when the request carries new inputs.
    var lastAttemptFailed = hasSavedFailure(request.mediaFileId());
    enqueue(request);
    if (!lastAttemptFailed) {
      return;
    }

    var instance = task.instance(request.mediaFileId().toString(), request);
    var now = clock.instant();
    client
        .getScheduledExecution(instance)
        .filter(pending -> !pending.isPicked())
        .filter(pending -> now.isBefore(pending.getExecutionTime()))
        .ifPresent(_ -> replacePendingInputs(instance, request, now));
  }

  private boolean hasSavedFailure(UUID mediaFileId) {
    return outcomes.findProbeStates(List.of(mediaFileId)).stream()
        .anyMatch(state -> state.failure().isPresent());
  }

  private void enqueue(ProbeTaskRequest request) {
    if (!outcomes.trySaveProbeRequest(request.mediaFileId(), request.inputs())) {
      return;
    }

    var instance = task.instance(request.mediaFileId().toString(), request);
    if (client.scheduleIfNotExists(instance, clock.instant())) {
      return;
    }

    var pending = client.getScheduledExecution(instance);
    if (pending
        .filter(existing -> existing.isPicked() || request.equals(existing.getData()))
        .isPresent()) {
      return;
    }

    var now = clock.instant();
    replacePendingInputs(
        instance,
        request,
        pending.map(ScheduledExecution::getExecutionTime).filter(now::isBefore).orElse(now));
  }

  private void replacePendingInputs(
      TaskInstance<ProbeTaskRequest> instance, ProbeTaskRequest request, Instant executionTime) {
    try {
      client.reschedule(instance, executionTime, request);
    } catch (TaskInstanceCurrentlyExecutingException _) {
      // Completion compares the retained desired inputs before removing the running instance.
    } catch (TaskInstanceNotFoundException _) {
      client.scheduleIfNotExists(instance, clock.instant());
    }
  }
}
