package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record ChangePasswordCommand(String currentPassword, String newPassword) {

  public static class ChangePasswordCommandBuilder {

    @Override
    public String toString() {
      return "ChangePasswordCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "ChangePasswordCommand[REDACTED]";
  }
}
