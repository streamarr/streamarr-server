package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library Operation Task Definition Tests")
class LibraryOperationTaskDefinitionTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID LIBRARY_OPERATION_ID = UUID.randomUUID();

  private final LibraryOperationExecution execution =
      LibraryOperationExecution.builder()
          .libraryOperations(new FakeLibraryOperationRepository(new FakeLibraryRepository()))
          .build();
  private final Task<UUID> task = LibraryOperationTaskDefinition.create(execution, CLOCK);
  private final FakeTaskRepository libraryOperationTasks = new FakeTaskRepository();

  @Test
  @DisplayName("Should remove the task when the execution ends")
  void shouldRemoveTheTaskWhenTheExecutionEnds() {
    var scheduled = scheduledExecution(0);

    task.execute(
            LibraryOperationTaskDefinition.instance(task, LIBRARY_OPERATION_ID),
            new ExecutionContext(
                new SchedulerState.SettableSchedulerState(), scheduled, null, null))
        .complete(ExecutionComplete.success(scheduled, NOW, NOW), operations(scheduled));

    assertThat(libraryOperationTasks.removed()).containsExactly(scheduled);
    assertThat(libraryOperationTasks.rescheduled()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should clear the shutdown's interrupt and retry after the backoff when an execution throws")
  void shouldClearTheShutdownsInterruptAndRetryAfterTheBackoffWhenAnExecutionThrows() {
    var scheduled = scheduledExecution(1);
    var failure =
        ExecutionComplete.failure(
            scheduled, NOW, NOW, new IllegalStateException("database unavailable"));

    Thread.currentThread().interrupt();
    try {
      task.getFailureHandler().onFailure(failure, operations(scheduled));

      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    } finally {
      Thread.interrupted();
    }

    assertThat(libraryOperationTasks.rescheduled())
        .containsExactly(
            RescheduleUpdate.toExecutionTime(NOW.plusSeconds(10))
                .lastSuccess(null)
                .lastFailure(NOW)
                .consecutiveFailures(2)
                .build());
  }

  @Test
  @DisplayName("Should reject a task definition when a collaborator is missing")
  void shouldRejectATaskDefinitionWhenACollaboratorIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> LibraryOperationTaskDefinition.create(null, CLOCK))
        .withMessageContaining("execution");
    assertThatNullPointerException()
        .isThrownBy(() -> LibraryOperationTaskDefinition.create(execution, null))
        .withMessageContaining("clock");
  }

  @Test
  @DisplayName("Should reject a scheduled task when its task or its library operation is missing")
  void shouldRejectAScheduledTaskWhenItsTaskOrItsLibraryOperationIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> LibraryOperationTaskDefinition.instance(null, LIBRARY_OPERATION_ID))
        .withMessageContaining("task");
    assertThatNullPointerException()
        .isThrownBy(() -> LibraryOperationTaskDefinition.instance(task, null))
        .withMessageContaining("libraryOperationId");
  }

  private ExecutionOperations<UUID> operations(Execution scheduled) {
    return new ExecutionOperations<>(libraryOperationTasks, SchedulerListeners.NOOP, scheduled);
  }

  private Execution scheduledExecution(int consecutiveFailures) {
    var instance = LibraryOperationTaskDefinition.instance(task, LIBRARY_OPERATION_ID);
    return new Execution(NOW, instance, true, "server", null, null, consecutiveFailures, NOW, 1L);
  }
}
