package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Bruno Auth Workflow Tests")
class BrunoAuthWorkflowTest {

  @Test
  @DisplayName("Should present stored refresh token when revoking session")
  void shouldPresentStoredRefreshTokenWhenRevokingSession() throws IOException {
    var logoutRequest = Files.readString(Path.of("bruno/Auth/Session/Logout.bru"));

    assertThat(logoutRequest)
        .contains(
            "url: {{BASE_URL}}/api/auth/refresh/revoke",
            "body: json",
            "auth: none",
            "content-type: application/json",
            "\"refreshToken\": \"{{REFRESH_TOKEN}}\"");
  }

  @Test
  @DisplayName("Should echo CSRF token when revoking session with Bruno cookies")
  void shouldEchoCsrfTokenWhenRevokingSessionWithBrunoCookies() throws IOException {
    var logoutRequest = Files.readString(Path.of("bruno/Auth/Session/Logout.bru"));

    assertThat(logoutRequest)
        .contains(
            "bru.cookies.get(\"__Host-XSRF-TOKEN\") || bru.cookies.get(\"XSRF-TOKEN\")",
            "req.setHeader(\"X-XSRF-TOKEN\", csrfToken)");
  }
}
