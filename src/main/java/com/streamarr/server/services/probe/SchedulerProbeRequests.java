package com.streamarr.server.services.probe;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The single entry point for requesting a probe. Requests are idempotent per media file: a pending
 * instance keeps its inputs unless the request carries new ones, and a running execution re-checks
 * the source itself.
 */
@Service
@RequiredArgsConstructor
public class SchedulerProbeRequests implements ProbeRequests {

  @Qualifier("probeSchedulerClient")
  private final SchedulerClient client;

  private final Task<ProbeRequest> task;
  private final MediaFileContainerInfoRepository outcomes;
  private final Clock clock;

  @Override
  public void request(ProbeRequest request) {
    outcomes.invalidateOutcomeUnlessSnapshotMatches(request.mediaFileId(), request.snapshot());
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

  private void replacePendingInputs(TaskInstance<ProbeRequest> instance, ProbeRequest request) {
    try {
      client.reschedule(instance, clock.instant(), request);
    } catch (TaskInstanceCurrentlyExecutingException _) {
      // The running execution compares the source snapshot itself and reschedules on change.
    } catch (TaskInstanceNotFoundException _) {
      client.scheduleIfNotExists(instance, clock.instant());
    }
  }
}
