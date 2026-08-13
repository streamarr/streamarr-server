package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagerOverrideCommand(
    UUID actingAccountId,
    UUID targetAccountId,
    UUID profileId,
    ProfileManagerOverrideAction action,
    String password,
    String reason) {

  public static class ProfileManagerOverrideCommandBuilder {
    @Override
    public String toString() {
      return "ProfileManagerOverrideCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "ProfileManagerOverrideCommand[actingAccountId=%s, targetAccountId=%s, profileId=%s, action=%s, reason=%s]"
        .formatted(actingAccountId, targetAccountId, profileId, action, reason);
  }
}
