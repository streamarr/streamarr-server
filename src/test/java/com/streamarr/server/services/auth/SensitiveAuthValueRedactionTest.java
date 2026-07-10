package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.controllers.auth.AuthTokensResponse;
import com.streamarr.server.controllers.auth.ChangePasswordRequest;
import com.streamarr.server.controllers.auth.LoginRequest;
import com.streamarr.server.controllers.auth.RefreshRequest;
import com.streamarr.server.controllers.auth.SetupRequest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Sensitive Auth Value Redaction Tests")
class SensitiveAuthValueRedactionTest {

  private static final String SECRET_MARKER = UUID.randomUUID().toString();

  @ParameterizedTest(name = "Should not expose secret-bearing auth value {index}")
  @MethodSource("secretBearingValues")
  @DisplayName("Should not expose secrets in auth value string representations")
  void shouldNotExposeSecretsInAuthValueStringRepresentations(Object value) {
    assertThat(value.toString()).doesNotContain(SECRET_MARKER);
  }

  @ParameterizedTest(name = "Should omit password field labels from auth value {index}")
  @MethodSource("passwordBearingValues")
  @DisplayName("Should omit password fields from auth value string representations")
  void shouldOmitPasswordFieldsFromAuthValueStringRepresentations(Object value) {
    assertThat(value.toString()).doesNotContainIgnoringCase("password=");
  }

  private static Stream<Object> secretBearingValues() {
    var account = AccountFixture.defaultAccountBuilder().build();
    var session = AuthSession.builder().accountId(UUID.randomUUID()).deviceName("device").build();

    return Stream.of(
        new LoginRequest("user@example.com", SECRET_MARKER, "device", false),
        new SetupRequest("user@example.com", "User", SECRET_MARKER, "Home", "Profile", false),
        new ChangePasswordRequest(SECRET_MARKER, SECRET_MARKER, false),
        new RefreshRequest(SECRET_MARKER, false),
        new TokenRefreshService.RefreshedTokens(null, SECRET_MARKER),
        ChangePasswordCommand.builder().currentPassword(SECRET_MARKER).newPassword(SECRET_MARKER),
        PasswordChangeResult.builder().rawRefreshToken(SECRET_MARKER),
        AuthTokensResponse.builder().accessToken(SECRET_MARKER).refreshToken(SECRET_MARKER),
        ChangePasswordCommand.builder()
            .accountId(UUID.randomUUID())
            .sessionId(UUID.randomUUID())
            .currentPassword(SECRET_MARKER)
            .newPassword(SECRET_MARKER)
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
            .build());
  }

  private static Stream<Object> passwordBearingValues() {
    return Stream.of(
        new LoginRequest("user@example.com", SECRET_MARKER, "device", false),
        new SetupRequest("user@example.com", "User", SECRET_MARKER, "Home", "Profile", false),
        new ChangePasswordRequest(SECRET_MARKER, SECRET_MARKER, false),
        ChangePasswordCommand.builder()
            .accountId(UUID.randomUUID())
            .sessionId(UUID.randomUUID())
            .currentPassword(SECRET_MARKER)
            .newPassword(SECRET_MARKER)
            .build());
  }
}
