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
    /**
     * Returns a redacted representation of the builder.
     *
     * @return a constant representation that excludes builder contents
     */
    @Override
    public String toString() {
      return "ForceProfileDeletionCommandBuilder[REDACTED]";
    }
  }

  /**
   * Formats the command with its account ID, profile ID, and deletion reason.
   *
   * @return a string representation that excludes the password
   */
  @Override
  public String toString() {
    return "ForceProfileDeletionCommand[actingAccountId=%s, profileId=%s, reason=%s]"
        .formatted(actingAccountId, profileId, reason);
  }
}
