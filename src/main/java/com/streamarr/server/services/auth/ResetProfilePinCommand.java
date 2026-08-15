package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ResetProfilePinCommand(
    @NonNull UUID actingAccountId, @NonNull UUID profileId, @NonNull String pinHash) {

  public static class ResetProfilePinCommandBuilder {
    /**
     * Provides a redacted representation of the command builder.
     *
     * @return a string that does not expose PIN data
     */
    @Override
    public String toString() {
      return "ResetProfilePinCommandBuilder[REDACTED]";
    }
  }

  /**
   * Formats the command with its acting account and profile identifiers while redacting the PIN hash.
   *
   * @return the command representation with the PIN hash redacted
   */
  @Override
  public String toString() {
    return "ResetProfilePinCommand[actingAccountId=%s, profileId=%s, pinHash=<redacted>]"
        .formatted(actingAccountId, profileId);
  }
}
