package com.streamarr.server.fakes;

import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.RescheduleUpdate;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkPriority;
import com.streamarr.server.services.ArtworkScheduling;
import com.streamarr.server.services.ArtworkSteps;
import com.streamarr.server.services.ArtworkStepsCompletion;
import com.streamarr.server.services.ArtworkStepsOutcome;
import com.streamarr.server.services.ArtworkStepsTask;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Keeps scheduled artwork steps in memory and runs them through the real {@link ArtworkSteps} on
 * the calling thread. The real {@link ArtworkStepsCompletion} completes each attempt: a removed
 * task leaves the pending targets, a task rescheduled to run at once without failures runs again,
 * and any other rescheduled task stays pending until a test runs it again. Unless a test holds
 * them, steps run as soon as they are scheduled.
 */
public class FakeArtworkScheduling implements ArtworkScheduling {

  private final Set<ArtworkTarget> pending = ConcurrentHashMap.newKeySet();
  private final Semaphore unfinishedAttempts = new Semaphore(0);
  private final FakeTaskRepository artworkTasks = new FakeTaskRepository();
  private volatile ArtworkSteps steps;
  private volatile ArtworkStepsCompletion completion;
  private volatile Clock clock;
  private volatile boolean runWhenScheduled = true;
  private volatile boolean runBackgroundWhenScheduled = true;

  public void runStepsWith(ArtworkSteps steps, ArtworkRequestRepository requests, Clock clock) {
    this.steps = steps;
    this.clock = clock;
    this.completion =
        ArtworkStepsCompletion.builder()
            .requests(requests)
            .transactionManager(new FakeTransactionManager())
            .artworkTasks(artworkTasks)
            .clock(clock)
            .build();
  }

  /** Keeps scheduled steps pending until the test calls {@link #runPendingSteps}. */
  public void holdScheduledSteps() {
    runWhenScheduled = false;
  }

  /** Keeps scheduled person and company steps pending until the test runs them. */
  public void holdBackgroundSteps() {
    runBackgroundWhenScheduled = false;
  }

  @Override
  public void scheduleSteps(ArtworkTarget target) {
    pending.add(target);
    if (isHeld(target)) {
      return;
    }

    run(target);
  }

  private boolean isHeld(ArtworkTarget target) {
    if (!runWhenScheduled) {
      return true;
    }

    return !runBackgroundWhenScheduled
        && ArtworkPriority.of(target.targetType()) == ArtworkPriority.BACKGROUND;
  }

  /** Runs every pending target's steps once and returns how each attempt ended. */
  public List<ArtworkStepsOutcome> runPendingSteps() {
    return List.copyOf(pending).stream().map(this::run).toList();
  }

  public Set<ArtworkTarget> pendingTargets() {
    return Set.copyOf(pending);
  }

  /** Waits until an attempt ends with a step left to retry, failing after {@code bound}. */
  public void awaitUnfinishedAttempt(Duration bound) throws InterruptedException {
    if (!unfinishedAttempts.tryAcquire(bound.toNanos(), TimeUnit.NANOSECONDS)) {
      throw new AssertionError("No artwork attempt ended unfinished within " + bound);
    }
  }

  private ArtworkStepsOutcome run(ArtworkTarget target) {
    var outcome = steps.runSteps(target);
    var completed = complete(target, outcome);
    if (completed == Completion.REMOVED) {
      pending.remove(target);
      return outcome;
    }

    if (completed == Completion.RUN_AGAIN_NOW) {
      return run(target);
    }

    unfinishedAttempts.release();
    return outcome;
  }

  private enum Completion {
    REMOVED,
    RUN_AGAIN_NOW,
    LEFT_PENDING
  }

  // The fake task repository is shared, so one completion at a time reads what it did.
  private synchronized Completion complete(ArtworkTarget target, ArtworkStepsOutcome outcome) {
    var now = clock.instant();
    var execution =
        new Execution(
            now,
            new TaskInstance<>("artwork-steps", ArtworkStepsTask.instanceId(target), target),
            true,
            "fake-artwork-scheduling",
            null,
            null,
            0,
            now,
            1L);
    var removedBefore = artworkTasks.removed().size();
    completion
        .handlerFor(target, outcome)
        .complete(
            ExecutionComplete.success(execution, now, now),
            new ExecutionOperations<>(artworkTasks, SchedulerListeners.NOOP, execution));
    if (artworkTasks.removed().size() > removedBefore) {
      return Completion.REMOVED;
    }

    var runNow = RescheduleUpdate.toExecutionTime(now).resetFailures().build();
    if (artworkTasks.rescheduled().getLast().equals(runNow)) {
      return Completion.RUN_AGAIN_NOW;
    }

    return Completion.LEFT_PENDING;
  }
}
