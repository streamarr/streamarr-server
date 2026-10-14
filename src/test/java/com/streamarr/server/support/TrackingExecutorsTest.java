package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Tracking Executors Tests")
class TrackingExecutorsTest {

  private static final Duration SHORT_BOUND = Duration.ofMillis(100);
  private static final Duration LONG_BOUND = Duration.ofSeconds(5);

  private final TrackingExecutors executors = new TrackingExecutors();

  @Test
  @DisplayName("Should return once a submitted task has finished when awaiting quiet")
  void shouldReturnOnceASubmittedTaskHasFinishedWhenAwaitingQuiet() throws Exception {
    var ran = new AtomicBoolean();
    var release = new CountDownLatch(1);
    try (var executor = executors.get()) {
      executor.submit(
          () -> {
            release.await();
            ran.set(true);
            return null;
          });
      release.countDown();

      executors.awaitQuiet(LONG_BOUND);

      assertThat(ran).isTrue();
    }
  }

  @Test
  @DisplayName("Should fail when a submitted task outlives the bound")
  void shouldFailWhenASubmittedTaskOutlivesTheBound() throws Exception {
    var release = new CountDownLatch(1);
    try (var executor = executors.get()) {
      executor.submit(
          () -> {
            release.await();
            return null;
          });

      assertThatThrownBy(() -> executors.awaitQuiet(SHORT_BOUND))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("1 tasks were still running");
      release.countDown();
    }
  }

  @Test
  @DisplayName("Should count no task when a shut-down executor rejects it")
  void shouldCountNoTaskWhenAShutDownExecutorRejectsIt() throws Exception {
    var executor = executors.get();
    executor.shutdownNow();

    assertThatThrownBy(() -> executor.submit(() -> {}))
        .isInstanceOf(RejectedExecutionException.class);

    executors.awaitQuiet(SHORT_BOUND);
  }
}
