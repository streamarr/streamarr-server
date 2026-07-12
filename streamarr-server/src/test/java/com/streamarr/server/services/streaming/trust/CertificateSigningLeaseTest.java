package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Certificate Signing Lease Tests")
class CertificateSigningLeaseTest {

  private static final Instant DATABASE_TIME = Instant.parse("2026-07-12T12:00:00Z");

  @Test
  @DisplayName("Should reject a non-positive fencing epoch")
  void shouldRejectNonPositiveFencingEpoch() {
    assertThatThrownBy(
            () ->
                CertificateSigningLease.builder()
                    .operation(CertificateAuthorityOperation.BOOTSTRAP)
                    .ownerId(UUID.randomUUID())
                    .fencingEpoch(0L)
                    .databaseTime(DATABASE_TIME)
                    .leaseUntil(DATABASE_TIME.plusSeconds(30))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("epoch");
  }

  @Test
  @DisplayName("Should reject a lease that is not later than its database time")
  void shouldRejectLeaseThatIsNotLaterThanItsDatabaseTime() {
    for (var leaseUntil : java.util.List.of(DATABASE_TIME, DATABASE_TIME.minusNanos(1))) {
      assertThatThrownBy(
              () ->
                  CertificateSigningLease.builder()
                      .operation(CertificateAuthorityOperation.BOOTSTRAP)
                      .ownerId(UUID.randomUUID())
                      .fencingEpoch(1L)
                      .databaseTime(DATABASE_TIME)
                      .leaseUntil(leaseUntil)
                      .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("later");
    }
  }

  @Test
  @DisplayName("Should reject missing lease identity or database times")
  void shouldRejectMissingLeaseIdentityOrDatabaseTimes() {
    var incompleteLeases =
        java.util.List.of(
            validLeaseBuilder().operation(null),
            validLeaseBuilder().ownerId(null),
            validLeaseBuilder().databaseTime(null),
            validLeaseBuilder().leaseUntil(null));

    for (var incomplete : incompleteLeases) {
      assertThatThrownBy(incomplete::build).isInstanceOf(NullPointerException.class);
    }
  }

  private CertificateSigningLease.CertificateSigningLeaseBuilder validLeaseBuilder() {
    return CertificateSigningLease.builder()
        .operation(CertificateAuthorityOperation.BOOTSTRAP)
        .ownerId(UUID.randomUUID())
        .fencingEpoch(1L)
        .databaseTime(DATABASE_TIME)
        .leaseUntil(DATABASE_TIME.plusSeconds(30));
  }
}
