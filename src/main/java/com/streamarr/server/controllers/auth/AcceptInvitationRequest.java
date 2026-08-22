package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

/** Code and new password are secrets; consumed synchronously, never echoed. */
public record AcceptInvitationRequest(
    @NotBlank String code,
    @NotBlank String displayName,
    @NotBlank String password,
    Boolean cookieMode) {

  @Override
  public String toString() {
    return "AcceptInvitationRequest[code=REDACTED, displayName=%s, password=REDACTED,"
            .formatted(displayName)
        + " cookieMode=%s]".formatted(cookieMode);
  }
}
