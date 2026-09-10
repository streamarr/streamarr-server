package com.streamarr.server.services.auth;

import lombok.Builder;
import lombok.NonNull;

@Builder
public record RedeemPasswordResetCommand(
    String code, String newPassword, @NonNull String ipAddress) {

  @Override
  public String toString() {
    return "RedeemPasswordResetCommand[code=REDACTED, newPassword=REDACTED, ipAddress=%s]"
        .formatted(ipAddress);
  }

  public static class RedeemPasswordResetCommandBuilder {

    @Override
    public String toString() {
      return "RedeemPasswordResetCommandBuilder[REDACTED]";
    }
  }
}
