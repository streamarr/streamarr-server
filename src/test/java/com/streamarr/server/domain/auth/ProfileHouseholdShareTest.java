package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Household Share Tests")
class ProfileHouseholdShareTest {

  @Test
  @DisplayName("Should expose Optional empty when the invalidation reason is absent")
  void shouldExposeOptionalEmptyWhenInvalidationReasonIsAbsent() {
    var share = ProfileHouseholdShare.builder().build();

    assertThat(share.getInvalidationReason()).isEmpty();
  }

  @Test
  @DisplayName("Should project a stale pending offer as expired when read after its expiry")
  void shouldProjectStalePendingOfferAsExpiredWhenReadAfterItsExpiry() {
    var now = Instant.parse("2026-08-27T12:00:00Z");
    var offer =
        ProfileHouseholdShare.builder()
            .status(ProfileShareStatus.PENDING)
            .expiresAt(now.minusSeconds(1))
            .build();

    assertThat(offer.statusAt(now)).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(offer.statusAt(now.minusSeconds(2))).isEqualTo(ProfileShareStatus.PENDING);
  }

  @Test
  @DisplayName("Should keep the stored status when the share is not a pending offer")
  void shouldKeepStoredStatusWhenShareIsNotPendingOffer() {
    var now = Instant.parse("2026-08-27T12:00:00Z");
    var active =
        ProfileHouseholdShare.builder()
            .status(ProfileShareStatus.ACTIVE)
            .expiresAt(now.minusSeconds(1))
            .build();
    var openEnded = ProfileHouseholdShare.builder().status(ProfileShareStatus.PENDING).build();

    assertThat(active.statusAt(now)).isEqualTo(ProfileShareStatus.ACTIVE);
    assertThat(openEnded.statusAt(now)).isEqualTo(ProfileShareStatus.PENDING);
  }
}
