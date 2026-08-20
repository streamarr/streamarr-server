package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

/** Code and new password are secrets; consumed synchronously, never echoed. */
public record RedeemPasswordResetRequest(@NotBlank String code, @NotBlank String newPassword) {

  @Override
  public String toString() {
    return "RedeemPasswordResetRequest[code=REDACTED, newPassword=REDACTED]";
  }
}
