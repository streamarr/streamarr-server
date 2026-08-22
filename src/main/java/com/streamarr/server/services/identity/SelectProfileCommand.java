package com.streamarr.server.services.identity;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** The select-profile ceremony's input; the PIN is consumed and never echoed. */
@Builder
public record SelectProfileCommand(
    @NonNull UUID accountId, @NonNull UUID sessionId, @NonNull UUID profileId, String pin) {

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
