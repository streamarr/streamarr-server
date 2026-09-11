package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.domain.task.ProbeRequest;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The db-scheduler task that runs media file probes; one instance per media file id. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MediaProbeTask {

  public static final String NAME = "media-file-probe";

  public static Task<ProbeRequest> create(ProbeExecution execution) {
    return create(execution, Clock.systemUTC());
  }

  public static Task<ProbeRequest> create(ProbeExecution execution, Clock clock) {
    return Tasks.custom(NAME, ProbeRequest.class)
        .onFailure(new CappedExponentialBackoff<>(clock))
        .execute((instance, _) -> completion(execution.execute(instance.getData()), clock));
  }

  private static CompletionHandler<ProbeRequest> completion(
      ProbeExecutionResult result, Clock clock) {
    return switch (result) {
      case ProbeExecutionResult.Completed _ -> new CompletionHandler.OnCompleteRemove<>();
      case ProbeExecutionResult.Rescheduled(var request) ->
          (complete, operations) -> operations.reschedule(complete, clock.instant(), request);
    };
  }
}
