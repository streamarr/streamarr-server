package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record PasswordChangeCompletionCommand(
    UUID accountId, UUID sessionId, String expectedPasswordHash, String newPasswordHash) {

  public static class PasswordChangeCompletionCommandBuilder {

    @Override
    public String toString() {
      return "PasswordChangeCompletionCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "PasswordChangeCompletionCommand[accountId=%s, sessionId=%s, credentials=REDACTED]"
        .formatted(accountId, sessionId);
  }
}
