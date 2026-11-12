package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.streamarr.server.fakes.UnreachableDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe scheduler stop tests")
class ProbeSchedulerStopTest {

  private final Scheduler scheduler =
      Scheduler.create(new UnreachableDataSource()).threads(1).build();
  private final ProbeSchedulerStop probeSchedulerStop = new ProbeSchedulerStop(scheduler);

  @AfterEach
  void stopTheScheduler() {
    if (scheduler.getSchedulerState().isStarted()
        && !scheduler.getSchedulerState().isShuttingDown()) {
      scheduler.stop();
    }
  }

  @Test
  @DisplayName("Should report the probe scheduler stopped when it has not started")
  void shouldReportTheProbeSchedulerStoppedWhenItHasNotStarted() {
    assertThat(probeSchedulerStop.isRunning()).isFalse();
  }

  @Test
  @DisplayName("Should start the probe scheduler when the context starts it before it ran")
  void shouldStartTheProbeSchedulerWhenTheContextStartsItBeforeItRan() {
    probeSchedulerStop.start();

    assertThat(probeSchedulerStop.isRunning()).isTrue();
  }

  // A stopped db-scheduler has shut its executors down, so starting it again would throw.
  @Test
  @DisplayName("Should leave the probe scheduler stopped when the context starts it after its stop")
  void shouldLeaveTheProbeSchedulerStoppedWhenTheContextStartsItAfterItsStop() {
    probeSchedulerStop.start();
    probeSchedulerStop.stop();

    probeSchedulerStop.start();

    assertThat(probeSchedulerStop.isRunning()).isFalse();
  }
}
