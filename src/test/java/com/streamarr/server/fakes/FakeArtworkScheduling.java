package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkPriority;
import com.streamarr.server.services.ArtworkScheduling;
import com.streamarr.server.services.ArtworkSteps;
import com.streamarr.server.services.ArtworkStepsOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Keeps scheduled artwork steps in memory and runs them through the real {@link ArtworkSteps} on
 * the calling thread, completing each as the scheduled task's completion does: a finished
 * generation deletes its request, a newer generation runs again at once, and an unfinished or
 * interrupted attempt stays pending until a test runs it again. Unless a test holds them, steps run
 * as soon as they are scheduled.
 */
public class FakeArtworkScheduling implements ArtworkScheduling {

  private final Set<ArtworkItem> pending = ConcurrentHashMap.newKeySet();
  private final Semaphore unfinishedAttempts = new Semaphore(0);
  private volatile ArtworkSteps steps;
  private volatile ArtworkRequestRepository requests;
  private volatile boolean runWhenScheduled = true;
  private volatile boolean runBackgroundWhenScheduled = true;

  public void runStepsWith(ArtworkSteps steps, ArtworkRequestRepository requests) {
    this.steps = steps;
    this.requests = requests;
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
  public void scheduleSteps(ArtworkItem item) {
    pending.add(item);
    if (isHeld(item)) {
      return;
    }

    run(item);
  }

  private boolean isHeld(ArtworkItem item) {
    if (!runWhenScheduled) {
      return true;
    }

    return !runBackgroundWhenScheduled
        && ArtworkPriority.of(item.itemType()) == ArtworkPriority.BACKGROUND;
  }

  /** Runs every pending item's steps once and returns how each attempt ended. */
  public List<ArtworkStepsOutcome> runPendingSteps() {
    return List.copyOf(pending).stream().map(this::run).toList();
  }

  public Set<ArtworkItem> pendingItems() {
    return Set.copyOf(pending);
  }

  /** Waits until an attempt ends with a step left to retry, failing after {@code bound}. */
  public void awaitUnfinishedAttempt(Duration bound) throws InterruptedException {
    if (!unfinishedAttempts.tryAcquire(bound.toNanos(), TimeUnit.NANOSECONDS)) {
      throw new AssertionError("No artwork attempt ended unfinished within " + bound);
    }
  }

  private ArtworkStepsOutcome run(ArtworkItem item) {
    var outcome = steps.runSteps(item);
    switch (outcome) {
      case ArtworkStepsOutcome.NotRequested _ -> pending.remove(item);
      case ArtworkStepsOutcome.Finished(var generation) -> {
        if (!requests.tryCompleteGeneration(item, generation)) {
          return run(item);
        }

        pending.remove(item);
      }
      case ArtworkStepsOutcome.Unfinished _, ArtworkStepsOutcome.Interrupted _ ->
          unfinishedAttempts.release();
    }

    return outcome;
  }
}
