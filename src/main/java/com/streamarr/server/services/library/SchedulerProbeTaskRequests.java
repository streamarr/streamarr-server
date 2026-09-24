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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
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

  @Qualifier("probeSchedulerClient")
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
    enqueue(request);
    var instance = task.instance(request.mediaFileId().toString(), request);
    var now = clock.instant();
    client
        .getScheduledExecution(instance)
        .filter(pending -> !pending.isPicked())
        .filter(pending -> pending.getConsecutiveFailures() > 0)
        .filter(pending -> now.isBefore(pending.getExecutionTime()))
        .ifPresent(_ -> replacePendingInputs(instance, request, now));
  }

  private void enqueue(ProbeTaskRequest request) {
    if (!outcomes.recordProbeRequest(request.mediaFileId(), request.inputs())) {
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
