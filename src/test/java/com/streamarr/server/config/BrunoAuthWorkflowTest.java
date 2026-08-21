package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.support.BrunoRequestDocument;
import java.io.IOException;
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
    var logoutRequest = BrunoRequestDocument.parse(Path.of("bruno/Auth/Session/Logout.bru"));

    assertThat(logoutRequest.method()).isEqualTo("POST");
    assertThat(logoutRequest.url()).isEqualTo("{{BASE_URL}}/api/auth/refresh/revoke");
    assertThat(logoutRequest.bodyMode()).isEqualTo("json");
    assertThat(logoutRequest.authMode()).isEqualTo("none");
    assertThat(logoutRequest.headers()).containsEntry("content-type", "application/json");
    assertThat(logoutRequest.jsonBody().path("refreshToken").asText())
        .isEqualTo("{{REFRESH_TOKEN}}");
  }

  @Test
  @DisplayName("Should echo CSRF token when revoking session with Bruno cookies")
  void shouldEchoCsrfTokenWhenRevokingSessionWithBrunoCookies() throws IOException {
    var logoutRequest = BrunoRequestDocument.parse(Path.of("bruno/Auth/Session/Logout.bru"));

    assertThat(logoutRequest.preRequestCookieNames())
        .containsExactly("__Host-XSRF-TOKEN", "XSRF-TOKEN");
    assertThat(logoutRequest.preRequestHeaders()).containsEntry("X-XSRF-TOKEN", "csrfToken");
  }
}
