package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Bounded Task Tests")
class BoundedTaskTest {

  private static final Duration SHORT_BOUND = Duration.ofMillis(100);
  private static final Duration LONG_BOUND = Duration.ofSeconds(5);

  private final CountDownLatch neverReleased = new CountDownLatch(1);
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final CountDownLatch releaseStubbornTask = new CountDownLatch(1);

  @AfterEach
  void releaseStubbornTask() {
    releaseStubbornTask.countDown();
  }

  @Test
  @DisplayName("Should return the task's result when the task finishes within the bound")
  void shouldReturnTheTaskResultWhenTheTaskFinishesWithinTheBound() throws Exception {
    try (var task = BoundedTask.start(() -> "finished")) {
      assertThat(task.await(LONG_BOUND)).isEqualTo("finished");
    }
  }

  @Test
  @DisplayName("Should rethrow the task's own failure when the task throws")
  void shouldRethrowTheTaskOwnFailureWhenTheTaskThrows() {
    var failure = new IllegalStateException("scan failed");

    try (var task =
        BoundedTask.start(
            () -> {
              throw failure;
            })) {
      assertThatThrownBy(() -> task.await(LONG_BOUND)).isSameAs(failure);
    }
  }

  @Test
  @DisplayName("Should interrupt the task and fail when the task outlives the bound")
  void shouldInterruptTheTaskAndFailWhenTheTaskOutlivesTheBound() {
    try (var task = BoundedTask.start(this::waitUntilInterrupted)) {
      assertThatThrownBy(() -> task.await(SHORT_BOUND))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("did not finish within");
      assertThat(stopped.getCount()).as("task stopped before await failed").isZero();
    }
  }

  @Test
  @DisplayName("Should fail within a bound when the task ignores its interrupt")
  void shouldFailWithinABoundWhenTheTaskIgnoresItsInterrupt() {
    try (var task = BoundedTask.start(this::ignoreInterruptsUntilReleased)) {
      assertThatThrownBy(() -> task.await(SHORT_BOUND))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("did not stop within");
      releaseStubbornTask.countDown();
    }
  }

  @Test
  @DisplayName("Should stop the task when the task is closed before it finishes")
  void shouldStopTheTaskWhenTheTaskIsClosedBeforeItFinishes() {
    var task = BoundedTask.start(this::waitUntilInterrupted);

    task.close();

    assertThat(stopped.getCount()).as("task stopped before close returned").isZero();
  }

  @Test
  @DisplayName("Should stop the task when the waiting thread is interrupted")
  void shouldStopTheTaskWhenTheWaitingThreadIsInterrupted() {
    try (var task = BoundedTask.start(this::waitUntilInterrupted)) {
      Thread.currentThread().interrupt();

      assertThatThrownBy(() -> task.await(LONG_BOUND)).isInstanceOf(InterruptedException.class);
      assertThat(stopped.getCount()).as("task stopped before await failed").isZero();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName(
      "Should answer that the task awaits a signal when it waits for a latch without a deadline")
  void shouldAnswerThatTheTaskAwaitsASignalWhenItWaitsForALatchWithoutADeadline() {
    try (var task = BoundedTask.start(this::waitUntilInterrupted)) {
      await().atMost(LONG_BOUND).until(task::isAwaitingASignalWithoutDeadline);
    }
  }

  @Test
  @DisplayName(
      "Should answer that the task awaits no signal when it waits for a latch with a deadline")
  void shouldAnswerThatTheTaskAwaitsNoSignalWhenItWaitsForALatchWithADeadline() {
    try (var task = BoundedTask.start(() -> neverReleased.await(1, TimeUnit.MINUTES))) {
      await().atMost(LONG_BOUND).until(task::isWaiting);

      assertThat(task.isAwaitingASignalWithoutDeadline()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should answer that the task awaits no signal while it waits for a lock that another thread holds")
  void shouldAnswerThatTheTaskAwaitsNoSignalWhileItWaitsForALockThatAnotherThreadHolds() {
    var lock = new ReentrantLock();
    lock.lock();
    try (var task = BoundedTask.start(() -> lockThenWaitUntilInterrupted(lock))) {
      await().atMost(LONG_BOUND).until(() -> lock.hasQueuedThreads() && task.isWaiting());

      assertThat(task.isAwaitingASignalWithoutDeadline()).isFalse();
      lock.unlock();
      await().atMost(LONG_BOUND).until(task::isAwaitingASignalWithoutDeadline);
    } finally {
      if (lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    }
  }

  @Test
  @DisplayName(
      "Should never answer that the task awaits a signal when it only takes a contended lock and waits with deadlines")
  void
      shouldNeverAnswerThatTheTaskAwaitsASignalWhenItOnlyTakesAContendedLockAndWaitsWithDeadlines() {
    var lock = new ReentrantLock();
    try (var _ = BoundedTask.start(() -> takeTurnsUntilInterrupted(lock));
        var task = BoundedTask.start(() -> takeTurnsUntilInterrupted(lock))) {
      await().pollInterval(Duration.ZERO).atMost(LONG_BOUND).until(lock::hasQueuedThreads);

      await()
          .pollInterval(Duration.ZERO)
          .during(SHORT_BOUND.multipliedBy(3))
          .atMost(LONG_BOUND)
          .until(() -> !task.isAwaitingASignalWithoutDeadline());
    }
  }

  private Void waitUntilInterrupted() throws InterruptedException {
    try {
      neverReleased.await();
      return null;
    } finally {
      stopped.countDown();
    }
  }

  private Void lockThenWaitUntilInterrupted(ReentrantLock lock) throws InterruptedException {
    lock.lockInterruptibly();
    try {
      return waitUntilInterrupted();
    } finally {
      lock.unlock();
    }
  }

  // Alternates between the lock, which the other caller contends, and a wait with a deadline.
  private Void takeTurnsUntilInterrupted(ReentrantLock lock) throws InterruptedException {
    while (!Thread.currentThread().isInterrupted()) {
      lock.lockInterruptibly();
      try {
        neverReleased.await(1, TimeUnit.MILLISECONDS);
      } finally {
        lock.unlock();
      }
      neverReleased.await(1, TimeUnit.MILLISECONDS);
    }
    return null;
  }

  private Void ignoreInterruptsUntilReleased() {
    while (true) {
      try {
        if (releaseStubbornTask.await(1, TimeUnit.MINUTES)) {
          return null;
        }
      } catch (InterruptedException _) {
        // Simulates a task stuck in a call that does not respond to interrupts.
      }
    }
  }
}
