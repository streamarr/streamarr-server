package com.streamarr.server.services.identity;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** The select-profile ceremony's input; the PIN is consumed and never echoed. */
@Builder
public record SelectProfileCommand(@NonNull UUID profileId, String pin, @NonNull String ipAddress) {

  @Override
  public String toString() {
    return "SelectProfileCommand[profileId=%s, pin=REDACTED, ipAddress=%s]"
        .formatted(profileId, ipAddress);
  }

  public static class SelectProfileCommandBuilder {

    @Override
    public String toString() {
      return "SelectProfileCommandBuilder[REDACTED]";
    }
  }
}
