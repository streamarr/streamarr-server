package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

/** The presented code is a one-time credential; consumed synchronously, never echoed. */
public record InvitationCodeRequest(@NotBlank String code) {

  @Override
  public String toString() {
    return "InvitationCodeRequest[code=REDACTED]";
  }
}
