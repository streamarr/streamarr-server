package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record SetupCommand(
    String email, String displayName, String password, String householdName, String profileName) {

  @Override
  public String toString() {
    return "SetupCommand[email=%s, displayName=%s, password=[REDACTED], householdName=%s, profileName=%s]"
        .formatted(email, displayName, householdName, profileName);
  }
}
