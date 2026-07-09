package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
    @NotBlank String currentPassword, @NotBlank String newPassword, boolean cookieMode) {

  @Override
  public String toString() {
    return "ChangePasswordRequest[currentPassword=REDACTED, newPassword=REDACTED, cookieMode=%s]"
        .formatted(cookieMode);
  }
}
