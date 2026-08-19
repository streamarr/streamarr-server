package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

/** The step-up ceremony's password; consumed synchronously and never echoed. */
public record ReauthRequest(@NotBlank String password) {

  @Override
  public String toString() {
    return "ReauthRequest[password=REDACTED]";
  }
}
