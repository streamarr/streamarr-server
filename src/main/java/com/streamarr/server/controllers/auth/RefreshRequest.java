package com.streamarr.server.controllers.auth;

public record RefreshRequest(String refreshToken, boolean cookieMode) {

  @Override
  public String toString() {
    return "RefreshRequest[refreshToken=REDACTED, cookieMode=%s]".formatted(cookieMode);
  }
}
