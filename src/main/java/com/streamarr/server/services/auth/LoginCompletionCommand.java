package com.streamarr.server.services.auth;

import java.util.Optional;
import java.util.UUID;
import lombok.Builder;

@Builder
public record LoginCompletionCommand(
    UUID accountId,
    String expectedPasswordHash,
    Optional<String> upgradedPasswordHash,
    String deviceName) {

  public static class LoginCompletionCommandBuilder {

    @Override
    public String toString() {
      return "LoginCompletionCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "LoginCompletionCommand[accountId=%s, credentials=[REDACTED], deviceName=%s]"
        .formatted(accountId, deviceName);
  }
}
