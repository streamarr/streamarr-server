package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Enrollment Grant Value Tests")
class EnrollmentGrantValueTest {

  @Test
  @DisplayName("Should reject enrollment grant request when lifetime is not positive")
  void shouldRejectEnrollmentGrantRequestWhenLifetimeIsNotPositive() {
    var zeroLifetime = requestBuilder().lifetime(Duration.ZERO);
    var negativeLifetime = requestBuilder().lifetime(Duration.ofSeconds(-1));

    assertThatIllegalArgumentException()
        .isThrownBy(zeroLifetime::build)
        .withMessage("Enrollment grant lifetime must be positive");
    assertThatIllegalArgumentException()
        .isThrownBy(negativeLifetime::build)
        .withMessage("Enrollment grant lifetime must be positive");
  }

  @Test
  @DisplayName("Should reject enrollment grant lifetime not representable by PostgreSQL")
  void shouldRejectEnrollmentGrantLifetimeNotRepresentableByPostgresql() {
    var belowOneMicrosecond = requestBuilder().lifetime(Duration.ofNanos(1));
    var fractionalMicrosecond = requestBuilder().lifetime(Duration.ofNanos(1_001));

    assertThatIllegalArgumentException()
        .isThrownBy(belowOneMicrosecond::build)
        .withMessage("Enrollment grant lifetime must use positive whole microseconds");
    assertThatIllegalArgumentException()
        .isThrownBy(fractionalMicrosecond::build)
        .withMessage("Enrollment grant lifetime must use positive whole microseconds");
  }

  @Test
  @DisplayName("Should reject public trust bundle reference when version is not positive")
  void shouldRejectPublicTrustBundleReferenceWhenVersionIsNotPositive() {
    var installationId = UUID.randomUUID();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PublicTrustBundleRef(installationId, 0L))
        .withMessage("Public trust bundle version must be positive");
  }

  @Test
  @DisplayName("Should reject enrollment grant when expiry does not follow creation")
  void shouldRejectEnrollmentGrantWhenExpiryDoesNotFollowCreation() {
    var timestamp = Instant.parse("2026-07-12T12:00:00Z");
    var grant =
        EnrollmentGrant.builder()
            .grantId(UUID.randomUUID())
            .workerId(UUID.randomUUID())
            .trustBundle(new PublicTrustBundleRef(UUID.randomUUID(), 1L))
            .createdAt(timestamp)
            .expiresAt(timestamp);

    assertThatIllegalArgumentException()
        .isThrownBy(grant::build)
        .withMessage("Enrollment grant expiry must follow creation");
  }

  @Test
  @DisplayName("Should reject grant result when public bundle does not match exact reference")
  void shouldRejectGrantResultWhenPublicBundleDoesNotMatchExactReference() {
    var installationId = UUID.randomUUID();
    var createdAt = Instant.parse("2026-07-12T12:00:00Z");
    var grant =
        EnrollmentGrant.builder()
            .grantId(UUID.randomUUID())
            .workerId(UUID.randomUUID())
            .trustBundle(new PublicTrustBundleRef(installationId, 1L))
            .createdAt(createdAt)
            .expiresAt(createdAt.plus(Duration.ofMinutes(10)))
            .build();
    var mismatchedBundle =
        PublicTrustBundle.builder()
            .installationId(installationId)
            .version(2L)
            .createdAt(createdAt)
            .trustAnchors(List.of())
            .issuers(List.of())
            .revocationSigners(List.of())
            .build();
    var mismatchedInstallationBundle =
        PublicTrustBundle.builder()
            .installationId(UUID.randomUUID())
            .version(1L)
            .createdAt(createdAt)
            .trustAnchors(List.of())
            .issuers(List.of())
            .revocationSigners(List.of())
            .build();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new GrantCreationResult.Created(grant, mismatchedBundle))
        .withMessage("Enrollment grant must carry its exact public bundle");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new GrantCreationResult.Retained(grant, mismatchedInstallationBundle))
        .withMessage("Enrollment grant must carry its exact public bundle");
  }

  private EnrollmentGrantRequest.EnrollmentGrantRequestBuilder requestBuilder() {
    return EnrollmentGrantRequest.builder()
        .workerId(UUID.randomUUID())
        .tokenSha256(new Sha256Digest(new byte[32]))
        .lifetime(Duration.ofMinutes(10));
  }
}
