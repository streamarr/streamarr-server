package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record SetupCommand(
    String email, String displayName, String password, String householdName, String profileName) {

  public static class SetupCommandBuilder {

    @Override
    public String toString() {
      return "SetupCommandBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "SetupCommand[email=%s, displayName=%s, householdName=%s, profileName=%s]"
        .formatted(email, displayName, householdName, profileName);
  }
}
