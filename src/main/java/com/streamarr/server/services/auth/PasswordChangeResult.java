package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record PasswordChangeResult(AccessToken accessToken, String rawRefreshToken) {

  public static class PasswordChangeResultBuilder {

    @Override
    public String toString() {
      return "PasswordChangeResultBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "PasswordChangeResult[accessToken=REDACTED, rawRefreshToken=REDACTED]";
  }
}
