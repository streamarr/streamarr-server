package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.DevicePairingService;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Secret record string representation tests")
class SecretRecordToStringTest {

  @Test
  @DisplayName("Should not expose plaintext secrets when builders are rendered")
  void shouldNotExposePlaintextSecretsWhenBuildersAreRendered() {
    var secret = UUID.randomUUID().toString();
    var renderedValues =
        List.of(
            LoginCommand.builder().password(secret).toString(),
            LoginCompletionCommand.builder().expectedPasswordHash(secret).toString(),
            SetupCommand.builder().password(secret).toString(),
            LoginResult.builder().rawRefreshToken(secret).toString(),
            AccessToken.builder().value(secret).toString(),
            RedeemPasswordResetCommand.builder().code(secret).newPassword(secret).toString(),
            AccountInvitationCeremonyService.AcceptInvitationCommand.builder()
                .code(secret)
                .password(secret)
                .toString(),
            AccountInvitationCeremonyService.InvitationCodeCommand.builder()
                .code(secret)
                .toString(),
            ProfileManagerAdministrationService.ManagerInvitationCodeCommand.builder()
                .code(secret)
                .toString(),
            DevicePairingService.PairingLookupCommand.builder().userCode(secret).toString(),
            DevicePairingService.PairingDecisionCommand.builder().userCode(secret).toString(),
            DeviceCodePresentation.builder().userCode(secret).toString());

    assertThat(renderedValues)
        .hasSize(12)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }

  @Test
  @DisplayName("Should not expose plaintext secrets in string representations when rendered")
  void shouldNotExposePlaintextSecretsInStringRepresentationsWhenRendered() {
    var secret = "review-secret-value";
    var renderedValues =
        List.of(
            LoginCommand.builder().password(secret).ipAddress("192.0.2.30").build().toString(),
            LoginCompletionCommand.builder()
                .expectedPasswordHash(secret)
                .upgradedPasswordHash(Optional.of(secret))
                .build()
                .toString(),
            SetupCommand.builder().password(secret).build().toString(),
            LoginResult.builder().rawRefreshToken(secret).build().toString(),
            new IssuedRefreshToken(secret, null).toString(),
            new RefreshResult.Rotated(secret, null).toString(),
            new RefreshResult.GraceRetry(secret, null).toString(),
            AccessToken.builder()
                .value(secret)
                .expiresAt(Instant.EPOCH)
                .scope(TokenScope.ACCOUNT)
                .build()
                .toString());

    assertThat(renderedValues)
        .hasSize(8)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }
}
