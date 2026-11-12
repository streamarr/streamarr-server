package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;

/**
 * Stops the probe scheduler together with the server's other background schedulers. The
 * db-scheduler starter starts it once the context is ready.
 */
@RequiredArgsConstructor
public final class ProbeSchedulerStop implements SmartLifecycle {

  @NonNull private final Scheduler scheduler;

  @Override
  public void start() {
    if (scheduler.getSchedulerState().isStarted()) {
      return;
    }

    scheduler.start();
  }

  @Override
  public void stop() {
    scheduler.stop();
  }

  @Override
  public void stop(@NonNull Runnable callback) {
    BackgroundWorkStop.onItsOwnThread("probe-scheduler-stop", this::stop, callback);
  }

  @Override
  public boolean isRunning() {
    var state = scheduler.getSchedulerState();
    return state.isStarted() && !state.isShuttingDown();
  }

  @Override
  public boolean isAutoStartup() {
    return false;
  }

  @Override
  public int getPhase() {
    return BackgroundWorkStop.PHASE;
  }
}
