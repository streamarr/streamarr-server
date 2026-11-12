package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.support.BoundedTask;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Background work stop tests")
class BackgroundWorkStopTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  @Test
  @DisplayName(
      "Should return before the stop ends and call back once it ended when the stop runs on its own"
          + " thread")
  void shouldReturnBeforeTheStopEndsAndCallBackOnceItEndedWhenTheStopRunsOnItsOwnThread()
      throws Exception {
    var stopMayEnd = new CountDownLatch(1);
    var calledBack = new CountDownLatch(1);

    BackgroundWorkStop.onItsOwnThread("stopping", () -> await(stopMayEnd), calledBack::countDown);

    assertThat(calledBack.getCount()).isOne();
    stopMayEnd.countDown();
    assertThat(calledBack.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("Should call back when the stop throws")
  void shouldCallBackWhenTheStopThrows() throws Exception {
    var calledBack = new CountDownLatch(1);

    BackgroundWorkStop.onItsOwnThread(
        "failing-stop",
        () -> {
          throw new IllegalStateException("the scheduler failed to stop");
        },
        calledBack::countDown);

    assertThat(calledBack.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  // Each stop waits until the other has started, so they end only when they run at once.
  @Test
  @DisplayName(
      "Should run every stop at once and return once each has returned when the stops run together")
  void shouldRunEveryStopAtOnceAndReturnOnceEachHasReturnedWhenTheStopsRunTogether()
      throws Exception {
    var started = new CountDownLatch(2);
    var ended = new CountDownLatch(2);
    Runnable stop =
        () -> {
          started.countDown();
          await(started);
          ended.countDown();
        };

    BoundedTask.runWithin(
        BOUND, () -> BackgroundWorkStop.together("stopping-", List.of(stop, stop)));

    assertThat(ended.getCount()).isZero();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
