package com.streamarr.server.services.auth;

import lombok.Builder;
import lombok.NonNull;

@Builder
public record LoginCommand(
    String email, String password, String deviceName, @NonNull String ipAddress) {

  public static class LoginCommandBuilder {

    @Override
    public String toString() {
      return "LoginCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "LoginCommand[email=%s, deviceName=%s, ipAddress=%s]"
        .formatted(email, deviceName, ipAddress);
  }
}
