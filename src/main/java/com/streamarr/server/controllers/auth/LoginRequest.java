package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank String email, @NotBlank String password, String deviceName, boolean cookieMode) {

  @Override
  public String toString() {
    return "LoginRequest[email=%s, deviceName=%s, cookieMode=%s]"
        .formatted(email, deviceName, cookieMode);
  }
}
