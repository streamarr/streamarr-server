package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ChangePasswordCommand(
    UUID accountId, UUID sessionId, String currentPassword, String newPassword) {

  public static class ChangePasswordCommandBuilder {

    @Override
    public String toString() {
      return "ChangePasswordCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "ChangePasswordCommand[accountId=%s, sessionId=%s, currentPassword=REDACTED,"
        + " newPassword=REDACTED]".formatted(accountId, sessionId);
  }
}
