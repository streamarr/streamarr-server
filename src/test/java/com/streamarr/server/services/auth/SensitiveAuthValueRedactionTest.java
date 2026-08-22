package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.controllers.auth.AuthTokensResponse;
import com.streamarr.server.controllers.auth.ChangePasswordRequest;
import com.streamarr.server.controllers.auth.LoginRequest;
import com.streamarr.server.controllers.auth.ReauthRequest;
import com.streamarr.server.controllers.auth.RefreshRequest;
import com.streamarr.server.controllers.auth.SelectProfileRequest;
import com.streamarr.server.controllers.auth.SetupRequest;
import com.streamarr.server.controllers.auth.device.DeviceAuthorizationResponse;
import com.streamarr.server.controllers.auth.device.DeviceCodeResponse;
import com.streamarr.server.controllers.auth.device.DeviceDecisionRequest;
import com.streamarr.server.controllers.auth.device.DeviceLookupRequest;
import com.streamarr.server.controllers.auth.device.DeviceTokenRequest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.DeviceAuthorizationDecisionCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertCommand;
import com.streamarr.server.services.identity.SelectProfileCommand;
import com.streamarr.server.services.identity.TokenRefreshService;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Sensitive Auth Value Redaction Tests")
class SensitiveAuthValueRedactionTest {

  private static final String SECRET_MARKER = UUID.randomUUID().toString();

  @ParameterizedTest(name = "Should hide secrets when rendering {0}")
  @MethodSource("secretBearingValues")
  @DisplayName("Should not expose secrets when rendering an auth value as a string")
  void shouldNotExposeSecretsWhenRenderingAuthValueAsString(Object value) {
    assertThat(value.toString()).doesNotContain(SECRET_MARKER);
  }

  @ParameterizedTest(name = "Should omit password field labels from auth value {index}")
  @MethodSource("passwordBearingValues")
  @DisplayName("Should omit password fields from auth value string representations")
  void shouldOmitPasswordFieldsFromAuthValueStringRepresentations(Object value) {
    assertThat(value.toString()).doesNotContainIgnoringCase("password=");
  }

  @Test
  @DisplayName("Should include safe metadata when rendering token response")
  void shouldIncludeSafeMetadataWhenRenderingTokenResponse() {
    var expiresAt = Instant.parse("2026-07-10T12:00:00Z");
    var response =
        AuthTokensResponse.builder()
            .accessToken(SECRET_MARKER)
            .accessTokenExpiresAt(expiresAt)
            .scope("profile")
            .refreshToken(SECRET_MARKER)
            .build();

    assertThat(response.toString())
        .contains("accessTokenExpiresAt=" + expiresAt, "scope=profile")
        .doesNotContain(SECRET_MARKER, "%s");
  }

  private static Stream<Named<Object>> secretBearingValues() {
    var account = AccountFixture.defaultAccountBuilder().build();
    var session = AuthSession.builder().accountId(UUID.randomUUID()).deviceName("device").build();

    return Stream.of(
            new LoginRequest("user@example.com", SECRET_MARKER, "device", false),
            new SetupRequest("user@example.com", "User", SECRET_MARKER, "Home", "Profile", false),
            new ChangePasswordRequest(SECRET_MARKER, SECRET_MARKER),
            new ReauthRequest(SECRET_MARKER),
            new RefreshRequest(SECRET_MARKER),
            new TokenRefreshService.RefreshedTokens(null, SECRET_MARKER),
            SelectProfileCommand.builder()
                .accountId(UUID.randomUUID())
                .sessionId(UUID.randomUUID())
                .profileId(UUID.randomUUID())
                .pin(SECRET_MARKER)
                .build(),
            SelectProfileCommand.builder().pin(SECRET_MARKER),
            new SelectProfileRequest(UUID.randomUUID(), SECRET_MARKER),
            // Pairing credentials: the device code is polled with, and the user code is low-entropy
            // enough that a log line naming it is a guess an attacker never has to make. Lombok's
            // generated builder toString is its own leakage surface, so unbuilt builders are here
            // too.
            new DeviceTokenRequest(SECRET_MARKER),
            new DeviceLookupRequest(SECRET_MARKER),
            new DeviceDecisionRequest(SECRET_MARKER, "APPROVE", null),
            DeviceCodeResponse.builder().deviceCode(SECRET_MARKER).userCode(SECRET_MARKER),
            IssuedDeviceCode.builder().deviceCode(SECRET_MARKER).userCode(SECRET_MARKER),
            DeviceAuthorizationResponse.builder().userCode(SECRET_MARKER).deviceName("Apple TV"),
            DeviceAuthorizationResponse.builder()
                .userCode(SECRET_MARKER)
                .deviceName("Apple TV")
                .status("PENDING")
                .build(),
            DeviceAuthorizationView.builder().userCode(SECRET_MARKER).deviceName("Apple TV"),
            DeviceAuthorizationView.builder()
                .userCode(SECRET_MARKER)
                .deviceName("Apple TV")
                .status(DeviceAuthorizationStatus.PENDING)
                .build(),
            DeviceDecisionCommand.builder()
                .userCode(SECRET_MARKER)
                .decision(DeviceDecision.APPROVE),
            DeviceDecisionCommand.builder()
                .userCode(SECRET_MARKER)
                .decision(DeviceDecision.APPROVE)
                .decidedByAccountId(UUID.randomUUID())
                .build(),
            DeviceAuthorizationDecisionCommand.builder()
                .userCode(SECRET_MARKER)
                .status(DeviceAuthorizationStatus.APPROVED),
            DeviceAuthorizationDecisionCommand.builder()
                .userCode(SECRET_MARKER)
                .status(DeviceAuthorizationStatus.APPROVED)
                .decidedByAccountId(UUID.randomUUID())
                .now(Instant.now())
                .build(),
            DeviceAuthorizationInsertCommand.builder()
                .deviceCodeDigest(SECRET_MARKER)
                .userCode(SECRET_MARKER),
            DeviceAuthorizationInsertCommand.builder()
                .deviceCodeDigest(SECRET_MARKER)
                .userCode(SECRET_MARKER)
                .deviceName("Apple TV")
                .expiresAt(Instant.now())
                .build(),
            DeviceCodeResponse.builder()
                .deviceCode(SECRET_MARKER)
                .userCode(SECRET_MARKER)
                .verificationUri("https://home.example.com/link")
                .interval(5)
                .expiresIn(600)
                .build(),
            IssuedDeviceCode.builder()
                .deviceCode(SECRET_MARKER)
                .userCode(SECRET_MARKER)
                .verificationUri("https://home.example.com/link")
                .interval(5)
                .expiresIn(600)
                .build(),
            new DevicePollResult.Success(
                new AccessToken(SECRET_MARKER, Instant.now(), TokenScope.ACCOUNT), SECRET_MARKER),
            ChangePasswordCommand.builder()
                .currentPassword(SECRET_MARKER)
                .newPassword(SECRET_MARKER),
            PasswordChangeCompletionCommand.builder()
                .expectedPasswordHash(SECRET_MARKER)
                .newPasswordHash(SECRET_MARKER),
            PasswordChangeResult.builder().rawRefreshToken(SECRET_MARKER),
            AuthTokensResponse.builder().accessToken(SECRET_MARKER).refreshToken(SECRET_MARKER),
            ChangePasswordCommand.builder()
                .currentPassword(SECRET_MARKER)
                .newPassword(SECRET_MARKER)
                .build(),
            PasswordChangeCompletionCommand.builder()
                .accountId(UUID.randomUUID())
                .sessionId(UUID.randomUUID())
                .expectedPasswordHash(SECRET_MARKER)
                .newPasswordHash(SECRET_MARKER)
                .build(),
            PasswordChangeResult.builder()
                .account(account)
                .session(session)
                .rawRefreshToken(SECRET_MARKER)
                .build(),
            AuthTokensResponse.builder()
                .accessToken(SECRET_MARKER)
                .accessTokenExpiresAt(Instant.now())
                .scope("account")
                .refreshToken(SECRET_MARKER)
                .build())
        .map(value -> Named.of(value.getClass().getSimpleName(), value));
  }

  private static Stream<Object> passwordBearingValues() {
    return Stream.of(
        new LoginRequest("user@example.com", SECRET_MARKER, "device", false),
        new SetupRequest("user@example.com", "User", SECRET_MARKER, "Home", "Profile", false),
        new ChangePasswordRequest(SECRET_MARKER, SECRET_MARKER),
        new ReauthRequest(SECRET_MARKER),
        ChangePasswordCommand.builder()
            .currentPassword(SECRET_MARKER)
            .newPassword(SECRET_MARKER)
            .build(),
        PasswordChangeCompletionCommand.builder()
            .accountId(UUID.randomUUID())
            .sessionId(UUID.randomUUID())
            .expectedPasswordHash(SECRET_MARKER)
            .newPasswordHash(SECRET_MARKER)
            .build());
  }
}
