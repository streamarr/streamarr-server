package com.streamarr.server.controllers.auth;

public record RefreshRequest(String refreshToken) {

  @Override
  public String toString() {
    return "RefreshRequest[refreshToken=REDACTED]";
  }
}
