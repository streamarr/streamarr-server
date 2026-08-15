package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ResetProfilePinCommand(
    @NonNull UUID actingAccountId, @NonNull UUID profileId, @NonNull String pinHash) {

  public static class ResetProfilePinCommandBuilder {
    @Override
    public String toString() {
      return "ResetProfilePinCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "ResetProfilePinCommand[actingAccountId=%s, profileId=%s, pinHash=<redacted>]"
        .formatted(actingAccountId, profileId);
  }
}
