package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record DeleteProfileCommand(UUID actingAccountId, UUID profileId, String password) {

  @Override
  public String toString() {
    return "DeleteProfileCommand[actingAccountId="
        + actingAccountId
        + ", profileId="
        + profileId
        + ", password=<redacted>]";
  }
}
