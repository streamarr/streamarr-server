package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.event.ExecutionChain;
import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.fakes.FakeTaskRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Release at shutdown tests")
class ReleaseAtShutdownTest {

  private static final Instant DUE = Instant.parse("2026-11-11T12:00:00Z");

  private final FakeTaskRepository tasks = new FakeTaskRepository();
  private final ReleaseAtShutdown release = new ReleaseAtShutdown(tasks);
  private final Task<Void> interruptedTask =
      Tasks.custom("interrupted", Void.class)
          .execute(
              (_, _) -> {
                throw new IllegalStateException("The shutdown closed the execution's connection");
              });
  private final Execution picked =
      new Execution(DUE, interruptedTask.instance("1"), true, "server", null, DUE, 2, DUE, 3L);
  private final SchedulerState.SettableSchedulerState schedulerState =
      new SchedulerState.SettableSchedulerState();

  @Test
  @DisplayName(
      "Should release the execution, with the shutdown's interrupt cleared, when it fails while its"
          + " scheduler shuts down")
  void shouldReleaseTheExecutionWhenItFailsWhileItsSchedulerShutsDown() {
    schedulerState.setIsShuttingDown();
    var completion = release.execute(picked.taskInstance, context(), chain());

    Thread.currentThread().interrupt();
    try {
      complete(completion);

      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    } finally {
      Thread.interrupted();
    }

    assertThat(tasks.released()).containsExactly(picked);
    assertThat(tasks.rescheduled()).isEmpty();
    assertThat(tasks.removed()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should hand the failure to the task when the execution fails while its scheduler runs")
  void shouldHandTheFailureToTheTaskWhenTheExecutionFailsWhileItsSchedulerRuns() {
    var context = context();
    var chain = chain();

    assertThatIllegalStateException()
        .isThrownBy(() -> release.execute(picked.taskInstance, context, chain));
    assertThat(tasks.released()).isEmpty();
  }

  private ExecutionContext context() {
    return new ExecutionContext(schedulerState, picked, null, null);
  }

  private ExecutionChain chain() {
    return new ExecutionChain(List.of(), interruptedTask);
  }

  // Completes the execution as db-scheduler completes an execution whose task returned.
  private <T> void complete(CompletionHandler<T> completion) {
    completion.complete(
        ExecutionComplete.success(picked, DUE, DUE),
        new ExecutionOperations<>(tasks, SchedulerListeners.NOOP, picked));
  }
}
