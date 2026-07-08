package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Login Throttle Sweeper Tests")
class LoginThrottleSweeperTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final LoginThrottle throttle =
      new LoginThrottle(
          AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ofMinutes(15)).build(),
          new MutableClock(currentTime));

  private final LoginThrottleSweeper sweeper = new LoginThrottleSweeper(throttle);

  @Test
  @DisplayName("Should leave nothing to evict when sweep already ran")
  void shouldLeaveNothingToEvictWhenSweepAlreadyRan() {
    throttle.registerAttempt("sprayed@example.com", "198.51.100.9");
    currentTime.updateAndGet(instant -> instant.plus(Duration.ofMinutes(16)));

    sweeper.sweep();

    assertThat(throttle.sweepExpired()).isZero();
  }
}
