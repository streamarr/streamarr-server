package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Device Poll Result Tests")
class DevicePollResultTest {

  @Test
  @DisplayName("Should require both credentials when constructing a successful poll result")
  void shouldRequireBothCredentialsWhenConstructingSuccessfulPollResult() {
    var accessToken = new AccessToken("access", Instant.EPOCH, TokenScope.ACCOUNT);

    assertThatNullPointerException()
        .isThrownBy(() -> new DevicePollResult.Success(null, "refresh"));
    assertThatNullPointerException()
        .isThrownBy(() -> new DevicePollResult.Success(accessToken, null));
  }
}
