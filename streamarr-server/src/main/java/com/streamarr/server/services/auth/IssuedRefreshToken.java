package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;

public record IssuedRefreshToken(String rawToken, AuthSession session) {

  @Override
  public String toString() {
    return "IssuedRefreshToken[rawToken=[REDACTED], session=%s]".formatted(session);
  }
}
