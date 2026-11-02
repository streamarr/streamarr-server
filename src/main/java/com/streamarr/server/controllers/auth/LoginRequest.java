package com.streamarr.server.controllers.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank String email,
    @NotBlank String password,
    String deviceName,
    @Schema(defaultValue = "false") Boolean cookieMode) {

  public LoginRequest {
    cookieMode = Boolean.TRUE.equals(cookieMode);
  }

  @Override
  public String toString() {
    return "LoginRequest[email=%s, deviceName=%s, cookieMode=%s]"
        .formatted(email, deviceName, cookieMode);
  }
}
