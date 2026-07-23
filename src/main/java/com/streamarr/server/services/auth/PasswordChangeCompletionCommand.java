package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PasswordChangeCompletionCommand(
    UUID accountId, UUID sessionId, String expectedPasswordHash, String newPasswordHash) {

  public PasswordChangeCompletionCommand {
    Objects.requireNonNull(accountId, "accountId is required");
    Objects.requireNonNull(sessionId, "sessionId is required");
    Objects.requireNonNull(expectedPasswordHash, "expectedPasswordHash is required");
    Objects.requireNonNull(newPasswordHash, "newPasswordHash is required");
  }

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
