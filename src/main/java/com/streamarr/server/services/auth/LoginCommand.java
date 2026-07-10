package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record LoginCommand(String email, String password, String deviceName, String source) {

  public static class LoginCommandBuilder {

    @Override
    public String toString() {
      return "LoginCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "LoginCommand[email=%s, deviceName=%s, source=%s]".formatted(email, deviceName, source);
  }
}
