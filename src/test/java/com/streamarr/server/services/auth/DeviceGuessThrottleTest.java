package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Device Guess Throttle Tests")
class DeviceGuessThrottleTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final MutableClock clock = new MutableClock(currentTime);

  private final DeviceAuthProperties properties =
      DeviceAuthProperties.builder()
          .codeTtl(Duration.ofMinutes(10))
          .pollIntervalSeconds(5)
          .verificationPath("/link")
          .maxOutstandingCodes(50)
          .maxGuessAttempts(3)
          .guessWindow(Duration.ofMinutes(15))
          .sweepInterval(Duration.ofMinutes(15))
          .build();

  private final DeviceGuessThrottle throttle = new DeviceGuessThrottle(properties, clock);

  @Test
  @DisplayName("Should allow attempts up to the configured budget")
  void shouldAllowAttemptsUpToConfiguredBudget() {
    var accountId = UUID.randomUUID();

    assertThatCode(
            () -> {
              throttle.registerAttempt(accountId);
              throttle.registerAttempt(accountId);
              throttle.registerAttempt(accountId);
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should let attempts through after the window")
  void shouldLetAttemptsThroughAfterWindow() {
    var accountId = UUID.randomUUID();
    exhaustBudget(accountId);

    advanceClock(properties.guessWindow().plusNanos(1));

    assertThatCode(() -> throttle.registerAttempt(accountId)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should budget each account separately")
  void shouldBudgetEachAccountSeparately() {
    var exhausted = UUID.randomUUID();
    exhaustBudget(exhausted);

    assertThatCode(() -> throttle.registerAttempt(UUID.randomUUID())).doesNotThrowAnyException();
    assertThatThrownBy(() -> throttle.registerAttempt(exhausted))
        .isInstanceOf(TooManyDeviceAttemptsException.class);
  }

  @Test
  @DisplayName("Should serialize concurrent attempts against the same account budget")
  void shouldSerializeConcurrentAttemptsAgainstSameAccountBudget() throws Exception {
    var accountId = UUID.randomUUID();
    var gatedClock = new GatedClock(clock);
    var concurrentThrottle = new DeviceGuessThrottle(properties, gatedClock);
    concurrentThrottle.registerAttempt(accountId);
    concurrentThrottle.registerAttempt(accountId);
    gatedClock.blockNextCall();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> register(concurrentThrottle, accountId));
      assertThat(gatedClock.awaitBlockedCall()).isTrue();

      var secondThread = new AtomicReference<Thread>();
      var secondStarted = new CountDownLatch(1);
      var second =
          executor.submit(
              () -> {
                secondThread.set(Thread.currentThread());
                secondStarted.countDown();
                return register(concurrentThrottle, accountId);
              });
      assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

      try {
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> second.isDone() || secondThread.get().getState() == Thread.State.BLOCKED);
        assertThat(second)
            .as("same-account attempt waits for the in-flight reservation")
            .isNotDone();
      } finally {
        gatedClock.releaseBlockedCall();
      }

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  @DisplayName("Should not extend a lockout with attempts made while already blocked")
  void shouldNotExtendLockoutWithAttemptsMadeWhileAlreadyBlocked() {
    var accountId = UUID.randomUUID();
    exhaustBudget(accountId);

    advanceClock(Duration.ofMinutes(14));
    assertThatThrownBy(() -> throttle.registerAttempt(accountId))
        .isInstanceOf(TooManyDeviceAttemptsException.class);

    // A blocked attempt reserves nothing, so hostile traffic cannot hold a victim out forever.
    advanceClock(Duration.ofMinutes(2));
    assertThatCode(() -> throttle.registerAttempt(accountId)).doesNotThrowAnyException();
  }

  private void exhaustBudget(UUID accountId) {
    for (var attempt = 0; attempt < properties.maxGuessAttempts(); attempt++) {
      throttle.registerAttempt(accountId);
    }
  }

  private void advanceClock(Duration duration) {
    currentTime.updateAndGet(instant -> instant.plus(duration));
  }

  private static boolean register(DeviceGuessThrottle throttle, UUID accountId) {
    try {
      throttle.registerAttempt(accountId);
      return true;
    } catch (TooManyDeviceAttemptsException _) {
      return false;
    }
  }

  private static final class GatedClock extends Clock {

    private final Clock delegate;
    private final AtomicBoolean blockNextCall = new AtomicBoolean();
    private final CountDownLatch blockedCall = new CountDownLatch(1);
    private final CountDownLatch releaseBlockedCall = new CountDownLatch(1);

    private GatedClock(Clock delegate) {
      this.delegate = delegate;
    }

    private void blockNextCall() {
      blockNextCall.set(true);
    }

    private boolean awaitBlockedCall() throws InterruptedException {
      return blockedCall.await(5, TimeUnit.SECONDS);
    }

    private void releaseBlockedCall() {
      releaseBlockedCall.countDown();
    }

    @Override
    public ZoneId getZone() {
      return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
      if (blockNextCall.compareAndSet(true, false)) {
        blockedCall.countDown();
        awaitRelease();
      }
      return delegate.instant();
    }

    private void awaitRelease() {
      try {
        releaseBlockedCall.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while holding the throttle race gate.");
      }
    }
  }
}
