package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ChangePasswordCommand(
    UUID accountId, UUID sessionId, String currentPassword, String newPassword) {

  @Override
  public String toString() {
    return "ChangePasswordCommand[accountId=%s, sessionId=%s, currentPassword=REDACTED,"
        + " newPassword=REDACTED]".formatted(accountId, sessionId);
  }
}
