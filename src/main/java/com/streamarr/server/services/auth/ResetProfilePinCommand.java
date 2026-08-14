package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ResetProfilePinCommand(UUID actingAccountId, UUID profileId, String pinHash) {

  public ResetProfilePinCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(pinHash, "pinHash");
  }

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
