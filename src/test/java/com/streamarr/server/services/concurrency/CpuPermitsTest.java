package com.streamarr.server.services.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.services.concurrency.CpuPermits.Priority;
import com.streamarr.server.support.BoundedTask;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("CPU permits")
class CpuPermitsTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  @Test
  @DisplayName("Should return the work's result and release its permit when the work finishes")
  void shouldReturnTheWorksResultAndReleaseItsPermitWhenTheWorkFinishes() throws Exception {
    var permits = new CpuPermits(1);

    var first = permits.runWithPermit(Priority.PRIMARY, () -> "poster");
    var second = permits.runWithPermit(Priority.BACKGROUND, () -> "profile");

    assertThat(List.of(first, second)).containsExactly("poster", "profile");
  }

  @Test
  @DisplayName("Should release the permit when the work fails")
  void shouldReleaseThePermitWhenTheWorkFails() throws Exception {
    var permits = new CpuPermits(1);

    assertThatThrownBy(
            () ->
                permits.runWithPermit(
                    Priority.PRIMARY,
                    () -> {
                      throw new IllegalStateException("undecodable");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(permits.runWithPermit(Priority.BACKGROUND, () -> "profile")).isEqualTo("profile");
  }

  @Test
  @DisplayName(
      "Should give a freed permit to a waiting primary acquirer before an earlier background one")
  void shouldGiveAFreedPermitToAWaitingPrimaryAcquirerBeforeAnEarlierBackgroundOne()
      throws Exception {
    var permits = new CpuPermits(1);
    var order = new CopyOnWriteArrayList<String>();
    var release = new CountDownLatch(1);

    try (var holder = holdPermit(permits, release);
        var background = BoundedTask.start(() -> run(permits, Priority.BACKGROUND, order));
        var primary = startWhenParked(background, () -> run(permits, Priority.PRIMARY, order))) {
      awaitParked(primary);
      release.countDown();

      holder.await(BOUND);
      primary.await(BOUND);
      background.await(BOUND);
    }

    assertThat(order).containsExactly("PRIMARY", "BACKGROUND");
  }

  @Test
  @DisplayName("Should give a background acquirer a permit when no primary acquirer waits")
  void shouldGiveABackgroundAcquirerAPermitWhenNoPrimaryAcquirerWaits() throws Exception {
    var permits = new CpuPermits(2);
    var release = new CountDownLatch(1);

    try (var holder = holdPermit(permits, release)) {
      assertThat(permits.runWithPermit(Priority.BACKGROUND, () -> "profile")).isEqualTo("profile");
      release.countDown();
      holder.await(BOUND);
    }
  }

  @Test
  @DisplayName(
      "Should let a background acquirer proceed when the waiting primary acquirer is interrupted")
  void shouldLetABackgroundAcquirerProceedWhenTheWaitingPrimaryAcquirerIsInterrupted()
      throws Exception {
    var permits = new CpuPermits(1);
    var order = new CopyOnWriteArrayList<String>();
    var release = new CountDownLatch(1);
    var primaryFailure = new AtomicReference<Throwable>();

    try (var holder = holdPermit(permits, release);
        var primary =
            BoundedTask.start(
                () -> {
                  try {
                    run(permits, Priority.PRIMARY, order);
                  } catch (InterruptedException e) {
                    primaryFailure.set(e);
                  }
                })) {
      awaitParked(primary);
      try (var background =
          startWhenParked(primary, () -> run(permits, Priority.BACKGROUND, order))) {
        awaitParked(background);
        primary.interrupt();
        primary.await(BOUND);
        release.countDown();

        holder.await(BOUND);
        background.await(BOUND);
      }
    }

    assertThat(primaryFailure.get()).isInstanceOf(InterruptedException.class);
    assertThat(order).containsExactly("BACKGROUND");
  }

  @Test
  @DisplayName("Should reject fewer than one permit")
  void shouldRejectFewerThanOnePermit() {
    assertThatThrownBy(() -> new CpuPermits(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject work without a priority")
  void shouldRejectWorkWithoutAPriority() {
    var permits = new CpuPermits(1);

    assertThatThrownBy(() -> permits.runWithPermit(null, () -> "poster"))
        .isInstanceOf(NullPointerException.class);
  }

  private static BoundedTask<Void> holdPermit(CpuPermits permits, CountDownLatch release)
      throws Exception {
    var held = new CountDownLatch(1);
    var holder =
        BoundedTask.start(
            () -> {
              permits.runWithPermit(
                  Priority.PRIMARY,
                  () -> {
                    held.countDown();
                    release.await();
                    return null;
                  });
            });
    assertThat(held.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
    return holder;
  }

  private static void run(CpuPermits permits, Priority priority, List<String> order)
      throws InterruptedException {
    permits.runWithPermit(
        priority,
        () -> {
          order.add(priority.name());
          return null;
        });
  }

  private static <T> BoundedTask<Void> startWhenParked(
      BoundedTask<T> earlier, BoundedTask.Action action) {
    awaitParked(earlier);
    return BoundedTask.start(action);
  }

  // A waiter parks inside the permits' condition, which exposes no hook; its thread state is the
  // only signal that it waits.
  private static void awaitParked(BoundedTask<?> task) {
    await().atMost(BOUND).until(task::isWaiting);
  }
}
