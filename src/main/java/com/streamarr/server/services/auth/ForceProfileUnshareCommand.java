package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ForceProfileUnshareCommand(
    @NonNull UUID actingAccountId,
    @NonNull UUID shareId,
    @NonNull String password,
    @NonNull String reason) {

  public static class ForceProfileUnshareCommandBuilder {
    /**
     * Provides a redacted representation of the builder.
     *
     * @return a placeholder that excludes builder field values
     */
    @Override
    public String toString() {
      return "ForceProfileUnshareCommandBuilder[REDACTED]";
    }
  }

  /**
   * Formats the command for display without exposing the password.
   *
   * @return a textual representation containing the acting account ID, share ID, and reason
   */
  @Override
  public String toString() {
    return "ForceProfileUnshareCommand[actingAccountId=%s, shareId=%s, reason=%s]"
        .formatted(actingAccountId, shareId, reason);
  }
}
