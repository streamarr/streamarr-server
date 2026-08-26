package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

  @Test
  @DisplayName("Should preserve a terminal invitation status after its expiry passes")
  void shouldPreserveTerminalInvitationStatusAfterItsExpiryPasses() {
    var invitation =
        AccountInvitation.builder()
            .status(AccountInvitationStatus.ACCEPTED)
            .expiresAt(NOW.minusSeconds(1))
            .build();

    assertThat(invitation.statusAt(NOW)).isEqualTo(AccountInvitationStatus.ACCEPTED);
  }

  @Test
  @DisplayName("Should project a stale pending reset code as expired when status is requested")
  void shouldProjectStalePendingResetCodeAsExpiredWhenStatusIsRequested() {
    var resetCode =
        PasswordResetCode.builder().status(PasswordResetCodeStatus.PENDING).expiresAt(NOW).build();

    assertThat(resetCode.statusAt(NOW)).isEqualTo(PasswordResetCodeStatus.EXPIRED);
    assertThat(resetCode.getStatus()).isEqualTo(PasswordResetCodeStatus.PENDING);
  }

  @Test
  @DisplayName("Should preserve a terminal reset-code status after its expiry passes")
  void shouldPreserveTerminalResetCodeStatusAfterItsExpiryPasses() {
    var resetCode =
        PasswordResetCode.builder()
            .status(PasswordResetCodeStatus.REDEEMED)
            .expiresAt(NOW.minusSeconds(1))
            .build();

    assertThat(resetCode.statusAt(NOW)).isEqualTo(PasswordResetCodeStatus.REDEEMED);
  }
}
