package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Device Authorization Sweeper Integration Tests")
class DeviceAuthorizationSweeperIT extends AbstractIntegrationTest {

  @Autowired private DeviceAuthorizationSweeper sweeper;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
  }

  @Test
  @DisplayName("Should delete expired rows of every status and keep live ones")
  void shouldDeleteExpiredRowsOfEveryStatusAndKeepLiveOnes() {
    save(DeviceAuthorizationStatus.PENDING, Duration.ofMinutes(-5));
    save(DeviceAuthorizationStatus.APPROVED, Duration.ofMinutes(-5));
    save(DeviceAuthorizationStatus.DENIED, Duration.ofMinutes(-5));
    save(DeviceAuthorizationStatus.CONSUMED, Duration.ofMinutes(-5));
    var live = save(DeviceAuthorizationStatus.PENDING, Duration.ofMinutes(10));

    sweeper.sweep();

    // Nothing reads a terminal row after the flow ends; retention would only grow the table and
    // the guessable-code surface.
    assertThat(authorizationRepository.findAll())
        .singleElement()
        .satisfies(row -> assertThat(row.getId()).isEqualTo(live));
  }

  @Test
  @DisplayName("Should free a user code for reuse once its row is swept")
  void shouldFreeUserCodeForReuseOnceItsRowIsSwept() {
    var userCode = "BCDFGHJK";
    saveWithUserCode(userCode, Duration.ofMinutes(-5));

    sweeper.sweep();

    // The UNIQUE constraint spans expired-but-unswept rows, so the sweep is what returns a code
    // to the pool.
    assertThat(saveWithUserCode(userCode, Duration.ofMinutes(10))).isNotNull();
  }

  private UUID save(DeviceAuthorizationStatus status, Duration untilExpiry) {
    return authorizationRepository
        .save(authorization(status, UUID.randomUUID().toString(), untilExpiry))
        .getId();
  }

  private UUID saveWithUserCode(String userCode, Duration untilExpiry) {
    return authorizationRepository
        .saveAndFlush(authorization(DeviceAuthorizationStatus.PENDING, userCode, untilExpiry))
        .getId();
  }

  private static DeviceAuthorization authorization(
      DeviceAuthorizationStatus status, String userCode, Duration untilExpiry) {
    var now = Instant.now();
    return DeviceAuthorization.builder()
        .deviceCodeDigest(UUID.randomUUID().toString())
        .userCode(userCode)
        .status(status)
        .deviceName("Apple TV")
        .expiresAt(now.plus(untilExpiry))
        .nextPollAt(now)
        .pollIntervalSeconds(5)
        .build();
  }
}
