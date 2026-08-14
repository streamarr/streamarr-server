package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record DeleteProfileCommand(UUID actingAccountId, UUID profileId, String password) {

  public DeleteProfileCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(password, "password");
  }

  public static class DeleteProfileCommandBuilder {
    @Override
    public String toString() {
      return "DeleteProfileCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "DeleteProfileCommand[actingAccountId="
        + actingAccountId
        + ", profileId="
        + profileId
        + ", password=<redacted>]";
  }
}
