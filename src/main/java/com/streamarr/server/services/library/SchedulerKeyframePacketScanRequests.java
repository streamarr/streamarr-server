package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import java.time.Clock;
import lombok.RequiredArgsConstructor;

/**
 * Schedules one keyframe packet scan execution per media file. When an execution already exists, it
 * reads the stored outcome when it runs, and its completion runs it again for a newer outcome that
 * still needs a scan.
 */
@RequiredArgsConstructor
public class SchedulerKeyframePacketScanRequests implements KeyframePacketScanRequests {

  private final SchedulerClient client;
  private final Task<KeyframePacketScanTaskRequest> task;
  private final Clock clock;

  @Override
  public void request(KeyframePacketScanTaskRequest request) {
    client.scheduleIfNotExists(
        task.instance(request.mediaFileId().toString(), request), clock.instant());
  }
}
