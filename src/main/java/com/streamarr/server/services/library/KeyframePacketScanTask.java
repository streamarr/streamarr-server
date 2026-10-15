package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The db-scheduler task that runs keyframe packet scans; one instance per media file id. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KeyframePacketScanTask {

  public static final String NAME = "media-file-keyframe-scan";

  public static Task<KeyframePacketScanTaskRequest> create(
      KeyframePacketScanExecution execution, KeyframePacketScanCompletion completion, Clock clock) {
    return Tasks.custom(NAME, KeyframePacketScanTaskRequest.class)
        .onFailure(new CappedExponentialBackoff<>(clock))
        .execute(
            (instance, _) ->
                completion.handlerFor(instance.getData(), execution.execute(instance.getData())));
  }
}
