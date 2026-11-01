package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The db-scheduler task that runs bitrate scans on the keyframe packet scan scheduler, one chunk
 * per execution; one instance per media file id.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BitrateScanTask {

  public static final String NAME = "media-file-bitrate-scan";

  public static Task<BitrateScanTaskRequest> create(
      BitrateScanExecution execution, BitrateScanCompletion completion, Clock clock) {
    return Tasks.custom(NAME, BitrateScanTaskRequest.class)
        .onFailure(new CappedExponentialBackoff<>(clock))
        .execute(
            (instance, _) ->
                completion.handlerFor(instance.getData(), execution.execute(instance.getData())));
  }
}
