package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.services.probe.ProbeAttemptEnds;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Signals the end of each probe attempt to the playback requests that wait for it. db-scheduler
 * calls the listener after the attempt's completion or failure handler has saved its result.
 */
@Component
@RequiredArgsConstructor
public class ProbeAttemptEndListener extends AbstractSchedulerListener {

  private final ProbeAttemptEnds attemptEnds;

  @Override
  public void onExecutionComplete(ExecutionComplete executionComplete) {
    var instance = executionComplete.getExecution().taskInstance;
    if (!MediaProbeTask.NAME.equals(instance.getTaskName())) {
      return;
    }

    attemptEnds.signalEnd(UUID.fromString(instance.getId()));
  }
}
