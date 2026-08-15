package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProfileManagerOverrideCommand(
    @NonNull UUID actingAccountId,
    @NonNull UUID targetAccountId,
    @NonNull UUID profileId,
    @NonNull ProfileManagerOverrideAction action,
    @NonNull String password,
    @NonNull String reason) {

  public static class ProfileManagerOverrideCommandBuilder {
    @Override
    public String toString() {
      return "ProfileManagerOverrideCommandBuilder[REDACTED]";
    }
  }

  /**
   * Formats the command as a string without exposing the password.
   *
   * @return a string containing the command's non-sensitive fields
   */
  @Override
  public String toString() {
    return "ProfileManagerOverrideCommand[actingAccountId=%s, targetAccountId=%s, profileId=%s, action=%s, reason=%s]"
        .formatted(actingAccountId, targetAccountId, profileId, action, reason);
  }
}
