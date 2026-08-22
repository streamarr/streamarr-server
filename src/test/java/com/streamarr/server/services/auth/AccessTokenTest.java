package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Access token tests")
class AccessTokenTest {

  @Test
  @DisplayName("Should reject access tokens with missing required fields")
  void shouldRejectAccessTokensWithMissingRequiredFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> new AccessToken(null, Instant.EPOCH, TokenScope.ACCOUNT))
        .withMessageContaining("value");
    assertThatNullPointerException()
        .isThrownBy(() -> new AccessToken("token", null, TokenScope.ACCOUNT))
        .withMessageContaining("expiresAt");
    assertThatNullPointerException()
        .isThrownBy(() -> new AccessToken("token", Instant.EPOCH, null))
        .withMessageContaining("scope");
  }
}
