package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.task.Priority;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The db-scheduler task that runs media file probes; one instance per media file id. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MediaProbeTask {

  public static final String NAME = "media-file-probe";

  public static Task<ProbeTaskRequest> create(
      ProbeExecution execution, ProbeTaskCompletion completion) {
    return create(execution, completion, Clock.systemUTC());
  }

  public static Task<ProbeTaskRequest> create(
      ProbeExecution execution, ProbeTaskCompletion completion, Clock clock) {
    return Tasks.custom(NAME, ProbeTaskRequest.class)
        .onFailure(completion.failureHandler(new CappedExponentialBackoff<>(clock)))
        .execute(
            (instance, _) ->
                completion.handlerFor(
                    instance.getData(),
                    execution.execute(instance.getData(), priorityOf(instance))));
  }

  // A playback request stores its probe at high priority, or raises the pending probe to it. A
  // raise
  // after the scheduler claimed the execution reaches only the next attempt.
  private static ProbePriority priorityOf(TaskInstance<ProbeTaskRequest> instance) {
    if (instance.getPriority() >= Priority.HIGH) {
      return ProbePriority.PLAYBACK;
    }

    return ProbePriority.BACKGROUND;
  }
}
