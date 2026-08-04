package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.fakes.FakeDeviceAuthorizationRepository;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Device Authorization Sweeper Tests")
class DeviceAuthorizationSweeperTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final MutableClock clock = new MutableClock(currentTime);
  private final FakeDeviceAuthorizationRepository repository =
      new FakeDeviceAuthorizationRepository();

  private final DeviceAuthorizationSweeper sweeper =
      new DeviceAuthorizationSweeper(repository, clock);

  @Test
  @DisplayName("Should delete every row past its lifetime regardless of terminal status")
  void shouldDeleteEveryRowPastItsLifetimeRegardlessOfTerminalStatus() {
    save(DeviceAuthorizationStatus.PENDING, Duration.ofMinutes(-1));
    save(DeviceAuthorizationStatus.APPROVED, Duration.ofMinutes(-1));
    save(DeviceAuthorizationStatus.DENIED, Duration.ofMinutes(-1));
    save(DeviceAuthorizationStatus.CONSUMED, Duration.ofMinutes(-1));
    var livePending = save(DeviceAuthorizationStatus.PENDING, Duration.ofMinutes(10));
    var liveApproved = save(DeviceAuthorizationStatus.APPROVED, Duration.ofMinutes(10));

    sweeper.sweep();

    assertThat(repository.findAll())
        .extracting(DeviceAuthorization::getId)
        .containsExactlyInAnyOrder(livePending, liveApproved);
  }

  @Test
  @DisplayName("Should leave unexpired rows alone")
  void shouldLeaveUnexpiredRowsAlone() {
    save(DeviceAuthorizationStatus.PENDING, Duration.ofMinutes(10));

    sweeper.sweep();

    assertThat(repository.findAll()).hasSize(1);
  }

  private UUID save(DeviceAuthorizationStatus status, Duration untilExpiry) {
    var now = currentTime.get();
    return repository
        .save(
            DeviceAuthorization.builder()
                .deviceCodeDigest(UUID.randomUUID().toString())
                .userCode(UUID.randomUUID().toString())
                .status(status)
                .deviceName("Apple TV")
                .expiresAt(now.plus(untilExpiry))
                .nextPollAt(now)
                .pollIntervalSeconds(5)
                .build())
        .getId();
  }
}
