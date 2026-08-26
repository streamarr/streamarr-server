package com.streamarr.server.services.auth;

import lombok.Builder;
import lombok.NonNull;

@Builder
public record ReauthenticationCommand(String password, @NonNull String ipAddress) {

  @Override
  public String toString() {
    return "ReauthenticationCommand[password=REDACTED, ipAddress=%s]".formatted(ipAddress);
  }

  public static class ReauthenticationCommandBuilder {

    @Override
    public String toString() {
      return "ReauthenticationCommandBuilder[REDACTED]";
    }
  }
}
