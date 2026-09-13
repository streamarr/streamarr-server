package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single entry point for requesting a probe. Requests are idempotent per media file: a pending
 * instance keeps its inputs unless the request carries new ones. Desired inputs remain durable
 * while an execution is running and are checked again when it completes.
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
    if (!outcomes.recordProbeRequest(
        request.mediaFileId(), new ProbeInputs(request.snapshot(), request.probeVersion()))) {
      return;
    }

    var instance = task.instance(request.mediaFileId().toString(), request);
    if (client.scheduleIfNotExists(instance, clock.instant())) {
      return;
    }

    if (client
        .getScheduledExecution(instance)
        .filter(existing -> existing.isPicked() || request.equals(existing.getData()))
        .isPresent()) {
      return;
    }

    replacePendingInputs(instance, request);
  }

  private void replacePendingInputs(
      TaskInstance<ProbeTaskRequest> instance, ProbeTaskRequest request) {
    try {
      client.reschedule(instance, clock.instant(), request);
    } catch (TaskInstanceCurrentlyExecutingException _) {
      // Completion compares the retained desired inputs before removing the running instance.
    } catch (TaskInstanceNotFoundException _) {
      client.scheduleIfNotExists(instance, clock.instant());
    }
  }
}
