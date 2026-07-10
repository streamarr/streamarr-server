package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

public record SetupRequest(
    @NotBlank String email,
    @NotBlank String displayName,
    @NotBlank String password,
    @NotBlank String householdName,
    @NotBlank String profileName,
    boolean cookieMode) {

  @Override
  public String toString() {
    return ("SetupRequest[email=%s, displayName=%s, householdName=%s, profileName=%s,"
            + " cookieMode=%s]")
        .formatted(email, displayName, householdName, profileName, cookieMode);
  }
}
