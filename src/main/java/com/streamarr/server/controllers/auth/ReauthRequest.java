package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

/** Password-bearing request whose diagnostic representation is redacted. */
public record ReauthRequest(@NotBlank String password) {

  @Override
  public String toString() {
    return "ReauthRequest[REDACTED]";
  }
}
