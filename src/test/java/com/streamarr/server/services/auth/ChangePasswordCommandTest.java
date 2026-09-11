package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Change Password Command Tests")
class ChangePasswordCommandTest {

  @Test
  @DisplayName("Should reject a missing new password when constructing the command")
  void shouldRejectMissingNewPasswordWhenConstructingCommand() {
    assertThatNullPointerException()
        .isThrownBy(() -> ChangePasswordCommand.builder().ipAddress("192.0.2.1").build())
        .withMessageContaining("newPassword");
  }
}
