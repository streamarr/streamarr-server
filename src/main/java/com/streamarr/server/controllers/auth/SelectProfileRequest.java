package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SelectProfileRequest(@NotNull UUID profileId, String pin) {

  /**
   * Formats the request with its profile ID while redacting the PIN.
   *
   * @return a string representation containing the profile ID and a redacted PIN
   */
  @Override
  public String toString() {
    return "SelectProfileRequest[profileId=%s, pin=<redacted>]".formatted(profileId);
  }
}
