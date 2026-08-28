package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Credential Status Projection Tests")
class CredentialStatusProjectionTest {

  private static final Instant NOW = Instant.parse("2026-08-26T16:00:00Z");

  @Test
  @DisplayName("Should expose only reset-code statuses supported by real transitions")
  void shouldExposeOnlyResetCodeStatusesSupportedByRealTransitions() {
    assertThat(PasswordResetCodeStatus.values())
        .containsExactly(
            PasswordResetCodeStatus.PENDING,
            PasswordResetCodeStatus.REDEEMED,
            PasswordResetCodeStatus.EXPIRED,
            PasswordResetCodeStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should project a stale pending invitation as expired when status is requested")
  void shouldProjectStalePendingInvitationAsExpiredWhenStatusIsRequested() {
    var invitation =
        AccountInvitation.builder().status(AccountInvitationStatus.PENDING).expiresAt(NOW).build();

    assertThat(invitation.statusAt(NOW)).isEqualTo(AccountInvitationStatus.EXPIRED);
    assertThat(invitation.getStatus()).isEqualTo(AccountInvitationStatus.PENDING);
  }

  @ParameterizedTest(name = "Should preserve {0} after an invitation expiry passes")
  @EnumSource(
      value = AccountInvitationStatus.class,
      names = {"ACCEPTED", "DECLINED", "CANCELED", "EXPIRED", "INVALIDATED"})
  @DisplayName("Should preserve a terminal invitation status after its expiry passes")
  void shouldPreserveTerminalInvitationStatusAfterItsExpiryPasses(AccountInvitationStatus status) {
    var invitation =
        AccountInvitation.builder().status(status).expiresAt(NOW.minusSeconds(1)).build();

    assertThat(invitation.statusAt(NOW)).isEqualTo(status);
  }

  @Test
  @DisplayName(
      "Should project a stale pending manager invitation as expired when status is requested")
  void shouldProjectStalePendingManagerInvitationAsExpiredWhenStatusIsRequested() {
    var invitation =
        ProfileManagerInvitation.builder()
            .status(ProfileManagerInvitationStatus.PENDING)
            .expiresAt(NOW)
            .build();

    assertThat(invitation.statusAt(NOW)).isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
    assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @ParameterizedTest(name = "Should preserve {0} when a manager-invitation expiry has passed")
  @EnumSource(
      value = ProfileManagerInvitationStatus.class,
      names = {"ACCEPTED", "DECLINED", "CANCELED", "EXPIRED", "INVALIDATED"})
  @DisplayName("Should preserve a terminal manager-invitation status when its expiry has passed")
  void shouldPreserveTerminalManagerInvitationStatusWhenItsExpiryHasPassed(
      ProfileManagerInvitationStatus status) {
    var invitation =
        ProfileManagerInvitation.builder().status(status).expiresAt(NOW.minusSeconds(1)).build();

    assertThat(invitation.statusAt(NOW)).isEqualTo(status);
  }

  @Test
  @DisplayName("Should project a stale pending reset code as expired when status is requested")
  void shouldProjectStalePendingResetCodeAsExpiredWhenStatusIsRequested() {
    var resetCode =
        PasswordResetCode.builder().status(PasswordResetCodeStatus.PENDING).expiresAt(NOW).build();

    assertThat(resetCode.statusAt(NOW)).isEqualTo(PasswordResetCodeStatus.EXPIRED);
    assertThat(resetCode.getStatus()).isEqualTo(PasswordResetCodeStatus.PENDING);
  }

  @ParameterizedTest(name = "Should preserve {0} after a reset-code expiry passes")
  @EnumSource(
      value = PasswordResetCodeStatus.class,
      names = {"REDEEMED", "EXPIRED", "INVALIDATED"})
  @DisplayName("Should preserve a terminal reset-code status after its expiry passes")
  void shouldPreserveTerminalResetCodeStatusAfterItsExpiryPasses(PasswordResetCodeStatus status) {
    var resetCode =
        PasswordResetCode.builder().status(status).expiresAt(NOW.minusSeconds(1)).build();

    assertThat(resetCode.statusAt(NOW)).isEqualTo(status);
  }
}
