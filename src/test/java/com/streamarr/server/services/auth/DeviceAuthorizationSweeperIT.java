package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.support.AuthTestSupport;
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

  @Autowired private DeviceRegistrationRepository registrationRepository;

  @Autowired private AuthTestSupport authTestSupport;

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
  }

  @Test
  @DisplayName(
      "Should name the System actor when the sweep clears an expired authorization from a"
          + " registration")
  void shouldNameSystemActorWhenSweepClearsAnExpiredAuthorizationFromARegistration() {
    var identity = authTestSupport.createIdentity();
    var expired = save(DeviceAuthorizationStatus.CONSUMED, Duration.ofMinutes(-5));
    var registration =
        registrationRepository.saveAndFlush(
            DeviceRegistration.builder()
                .esn("sweeper-esn-" + UUID.randomUUID())
                .displayName("Living Room TV")
                .householdId(identity.household().getId())
                .authorizingAccountId(identity.account().getId())
                .authorizationId(expired)
                .build());

    try {
      sweeper.sweep();

      var cleared = registrationRepository.findById(registration.getId()).orElseThrow();
      assertThat(cleared.getAuthorizationId()).isNull();
      assertThat(cleared.getLastModifiedBy()).isEqualTo(SystemActor.ID);
    } finally {
      registrationRepository.deleteById(registration.getId());
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should delete rows of every status and keep live ones when the sweeper runs")
  void shouldDeleteRowsOfEveryStatusAndKeepLiveOnesWhenSweeperRuns() {
    save(DeviceAuthorizationStatus.PENDING, Duration.ofMinutes(-5));
    save(DeviceAuthorizationStatus.APPROVED, Duration.ofMinutes(-5));
    save(DeviceAuthorizationStatus.DENIED, Duration.ofMinutes(-5));
    save(DeviceAuthorizationStatus.CONSUMED, Duration.ofMinutes(-5));
    var livePending = save(DeviceAuthorizationStatus.PENDING, Duration.ofMinutes(10));
    var liveApproved = save(DeviceAuthorizationStatus.APPROVED, Duration.ofMinutes(10));

    sweeper.sweep();

    assertThat(authorizationRepository.findAll())
        .extracting(DeviceAuthorization::getId)
        .containsExactlyInAnyOrder(livePending, liveApproved);
  }

  @Test
  @DisplayName("Should free a user code for reuse when its expired row is swept")
  void shouldFreeUserCodeForReuseWhenExpiredRowSwept() {
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
