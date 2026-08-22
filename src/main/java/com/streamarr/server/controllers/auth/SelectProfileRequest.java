package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The PIN is required only for a Profile with one set; it is consumed and never echoed. */
public record SelectProfileRequest(@NotNull UUID profileId, String pin) {

  @Override
  public String toString() {
    return "SelectProfileRequest[profileId=%s, pin=REDACTED]".formatted(profileId);
  }
}
