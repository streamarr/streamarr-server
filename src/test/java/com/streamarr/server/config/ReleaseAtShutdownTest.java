package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.event.ExecutionChain;
import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.ExecutionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.fakes.FakeTaskRepository;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Release at shutdown tests")
class ReleaseAtShutdownTest {

  private static final Instant DUE = Instant.parse("2026-11-11T12:00:00Z");
  private static final ExecutionHandler<Void> FAILING_ON_ITS_OWN =
      (_, _) -> {
        throw new IllegalStateException("The source file is gone");
      };

  private final FakeTaskRepository tasks = new FakeTaskRepository();
  private final ReleaseAtShutdown release = new ReleaseAtShutdown(tasks);
  private final SchedulerState.SettableSchedulerState schedulerState =
      new SchedulerState.SettableSchedulerState();

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  @DisplayName(
      "Should release the execution, with the shutdown's interrupt cleared, when the interrupt"
          + " fails it while its scheduler shuts down")
  void shouldReleaseTheExecutionWhenTheShutdownsInterruptFailsItWhileItsSchedulerShutsDown() {
    var picked =
        picked(
            (_, _) -> {
              // The scheduler's interrupt ends a wait, and the thread keeps the flag.
              Thread.currentThread().interrupt();
              throw new IllegalStateException("The interrupt closed the connection");
            });
    schedulerState.setIsShuttingDown();

    complete(picked, executeThroughTheRelease(picked));

    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    assertThat(tasks.released()).containsExactly(picked.execution());
    assertThat(tasks.rescheduled()).isEmpty();
    assertThat(tasks.removed()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("interruptCauses")
  @DisplayName(
      "Should release the execution when a failure that an interrupt caused ends it while its"
          + " scheduler shuts down")
  void shouldReleaseTheExecutionWhenAFailureThatAnInterruptCausedEndsItWhileItsSchedulerShutsDown(
      Exception interruptCause) {
    var picked =
        picked(
            (_, _) -> {
              throw new IllegalStateException("The interrupt ended a wait", interruptCause);
            });
    schedulerState.setIsShuttingDown();

    complete(picked, executeThroughTheRelease(picked));

    assertThat(tasks.released()).containsExactly(picked.execution());
  }

  @ParameterizedTest
  @MethodSource("failuresTheShutdownDidNotCause")
  @DisplayName(
      "Should hand the failure to the task when a failure that the shutdown did not cause ends the"
          + " execution while its scheduler shuts down")
  void shouldHandTheFailureToTheTaskWhenAFailureThatTheShutdownDidNotCauseEndsTheExecution(
      RuntimeException failure) {
    var picked =
        picked(
            (_, _) -> {
              throw failure;
            });
    schedulerState.setIsShuttingDown();

    assertThatThrownBy(() -> executeThroughTheRelease(picked)).isSameAs(failure);
    assertThat(tasks.released()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should hand the failure to the task when the execution fails while its scheduler runs")
  void shouldHandTheFailureToTheTaskWhenTheExecutionFailsWhileItsSchedulerRuns() {
    var picked = picked(FAILING_ON_ITS_OWN);

    assertThatIllegalStateException().isThrownBy(() -> executeThroughTheRelease(picked));
    assertThat(tasks.released()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should complete the execution with the shutdown's interrupt cleared when the interrupt"
          + " arrives after the task returned while its scheduler shuts down")
  void shouldCompleteTheExecutionWithTheInterruptClearedWhenTheInterruptArrivesAfterItReturned() {
    var picked = picked((_, _) -> removedUnlessInterrupted());
    schedulerState.setIsShuttingDown();
    var completion = executeThroughTheRelease(picked);

    Thread.currentThread().interrupt();
    complete(picked, completion);

    assertThat(tasks.removed()).containsExactly(picked.execution());
    assertThat(tasks.released()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should release the execution when its completion fails while its scheduler shuts down")
  void shouldReleaseTheExecutionWhenItsCompletionFailsWhileItsSchedulerShutsDown() {
    var picked =
        picked(
            (_, _) ->
                (_, _) -> {
                  throw new IllegalStateException("The database did not answer");
                });
    schedulerState.setIsShuttingDown();

    complete(picked, executeThroughTheRelease(picked));

    assertThat(tasks.released()).containsExactly(picked.execution());
    assertThat(tasks.removed()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should release the execution, with the shutdown's interrupt cleared, when its completion"
          + " began while its scheduler ran and the shutdown's interrupt fails it")
  void shouldReleaseTheExecutionWhenItsCompletionBeganWhileItsSchedulerRanAndTheInterruptFailsIt() {
    var picked =
        picked(
            (_, _) ->
                (_, _) -> {
                  // The completion waits for a row lock until the scheduler stops and interrupts
                  // it, and the interrupt ends the wait.
                  schedulerState.setIsShuttingDown();
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException("The interrupt closed the connection");
                });

    complete(picked, executeThroughTheRelease(picked));

    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    assertThat(tasks.released()).containsExactly(picked.execution());
    assertThat(tasks.removed()).isEmpty();
  }

  @Test
  @DisplayName("Should let a completion's failure through when the scheduler runs")
  void shouldLetACompletionsFailureThroughWhenTheSchedulerRuns() {
    var picked = picked((_, _) -> removedUnlessInterrupted());
    var completion = executeThroughTheRelease(picked);
    Thread.currentThread().interrupt();

    assertThatIllegalStateException().isThrownBy(() -> complete(picked, completion));
    assertThat(tasks.released()).isEmpty();
  }

  // An interrupt that ends a wait reaches the task as one of these causes.
  static Stream<Named<Exception>> interruptCauses() {
    return Stream.of(
        Named.of("a connection pool wait", new InterruptedException()),
        Named.of("a blocking stream read", new InterruptedIOException()),
        Named.of("a channel read", new ClosedByInterruptException()));
  }

  static Stream<Named<RuntimeException>> failuresTheShutdownDidNotCause() {
    return Stream.of(
        Named.of(
            "a failure of the task's own", new IllegalStateException("The source file is gone")));
  }

  // Saves the execution's end as a database write does: the connection pool refuses a connection
  // to an interrupted thread.
  private static CompletionHandler<Void> removedUnlessInterrupted() {
    return (_, operations) -> {
      if (Thread.currentThread().isInterrupted()) {
        throw new IllegalStateException("The pool refuses an interrupted thread");
      }

      operations.remove();
    };
  }

  private static Picked picked(ExecutionHandler<Void> handler) {
    var task = Tasks.custom("released", Void.class).execute(handler);
    return new Picked(
        task, new Execution(DUE, task.instance("1"), true, "server", null, DUE, 2, DUE, 3L));
  }

  private CompletionHandler<?> executeThroughTheRelease(Picked picked) {
    return release.execute(
        picked.execution().taskInstance,
        new ExecutionContext(schedulerState, picked.execution(), null, null),
        new ExecutionChain(List.of(), picked.handler()));
  }

  // Completes the execution as db-scheduler completes an execution whose task returned.
  private <T> void complete(Picked picked, CompletionHandler<T> completion) {
    completion.complete(
        ExecutionComplete.success(picked.execution(), DUE, DUE),
        new ExecutionOperations<>(tasks, SchedulerListeners.NOOP, picked.execution()));
  }

  private record Picked(ExecutionHandler<Void> handler, Execution execution) {}
}
