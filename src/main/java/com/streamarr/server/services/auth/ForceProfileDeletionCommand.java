package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ForceProfileDeletionCommand(
    UUID actingAccountId, UUID profileId, String password, String reason) {

  public ForceProfileDeletionCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(reason, "reason");
  }

  public static class ForceProfileDeletionCommandBuilder {
    @Override
    public String toString() {
      return "ForceProfileDeletionCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "ForceProfileDeletionCommand[actingAccountId=%s, profileId=%s, reason=%s]"
        .formatted(actingAccountId, profileId, reason);
  }
}
