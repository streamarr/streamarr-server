package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ForceProfileDeletionCommand(
    @NonNull UUID actingAccountId,
    @NonNull UUID profileId,
    @NonNull String password,
    @NonNull String reason) {

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
