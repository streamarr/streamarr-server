package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record DeleteProfileCommand(
    @NonNull UUID actingAccountId, @NonNull UUID profileId, @NonNull String password) {

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
