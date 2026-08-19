package com.streamarr.server.services.identity;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

/** The select-profile ceremony's input; the PIN is consumed and never echoed. */
@Builder
public record SelectProfileCommand(UUID accountId, UUID sessionId, UUID profileId, String pin) {

  public SelectProfileCommand {
    Objects.requireNonNull(accountId, "accountId is required");
    Objects.requireNonNull(sessionId, "sessionId is required");
    Objects.requireNonNull(profileId, "profileId is required");
  }

  @Override
  public String toString() {
    return "SelectProfileCommand[accountId=%s, sessionId=%s, profileId=%s, pin=REDACTED]"
        .formatted(accountId, sessionId, profileId);
  }

  public static class SelectProfileCommandBuilder {

    @Override
    public String toString() {
      return "SelectProfileCommandBuilder[REDACTED]";
    }
  }
}
