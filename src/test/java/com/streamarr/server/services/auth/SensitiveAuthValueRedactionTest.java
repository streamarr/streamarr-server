package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.controllers.auth.AuthTokensResponse;
import com.streamarr.server.controllers.auth.ChangePasswordRequest;
import com.streamarr.server.controllers.auth.LoginRequest;
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

  @ParameterizedTest(name = "Should redact secret-bearing auth value {index}")
  @MethodSource("secretBearingValues")
  @DisplayName("Should redact secrets from auth value string representations")
  void shouldRedactSecretsFromAuthValueStringRepresentations(Object value) {
    assertThat(value.toString()).doesNotContain(SECRET_MARKER).contains("REDACTED");
  }

  private static Stream<Object> secretBearingValues() {
    var account = AccountFixture.defaultAccountBuilder().build();
    var session = AuthSession.builder().accountId(UUID.randomUUID()).deviceName("device").build();

    return Stream.of(
        new LoginRequest("user@example.com", SECRET_MARKER, "device", false),
        new SetupRequest("user@example.com", "User", SECRET_MARKER, "Home", "Profile", false),
        new ChangePasswordRequest(SECRET_MARKER, SECRET_MARKER, false),
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
}
