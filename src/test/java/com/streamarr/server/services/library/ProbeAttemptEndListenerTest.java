package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.streamarr.server.services.probe.ProbeAttemptEnds;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe attempt end listener")
class ProbeAttemptEndListenerTest {

  private final UUID mediaFileId = UUID.randomUUID();
  private final ProbeAttemptEnds attemptEnds = new ProbeAttemptEnds();
  private final ProbeAttemptEndListener listener = new ProbeAttemptEndListener(attemptEnds);

  @Test
  @DisplayName("Should end the wait for the file's probe when an attempt at the probe completes")
  void shouldEndTheWaitForTheFilesProbeWhenAnAttemptAtTheProbeCompletes() throws Exception {
    var nextEnd = attemptEnds.next(mediaFileId);
    var otherFileEnd = attemptEnds.next(UUID.randomUUID());

    listener.onExecutionComplete(completed(MediaProbeTask.NAME));

    assertThat(nextEnd.await(Duration.ZERO)).isTrue();
    assertThat(otherFileEnd.await(Duration.ZERO)).isFalse();
  }

  @Test
  @DisplayName("Should keep waiting for the file's probe when another task's execution completes")
  void shouldKeepWaitingForTheFilesProbeWhenAnotherTasksExecutionCompletes() throws Exception {
    var nextEnd = attemptEnds.next(mediaFileId);

    listener.onExecutionComplete(completed("keyframe-packet-scan"));

    assertThat(nextEnd.await(Duration.ZERO)).isFalse();
  }

  private ExecutionComplete completed(String taskName) {
    var execution =
        new Execution(
            Instant.EPOCH, new TaskInstance<>(taskName, mediaFileId.toString(), (Void) null));
    return ExecutionComplete.success(execution, Instant.EPOCH, Instant.EPOCH);
  }
}
