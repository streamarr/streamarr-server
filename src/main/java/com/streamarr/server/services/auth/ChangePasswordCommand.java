package com.streamarr.server.services.auth;

import lombok.Builder;
import lombok.NonNull;

@Builder
public record ChangePasswordCommand(
    String currentPassword, String newPassword, @NonNull String ipAddress) {

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
