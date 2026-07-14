package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;

public sealed interface RefreshResult {

  AuthSession session();

  /** A genuine rotation: the caller receives the one new refresh token for this session. */
  record Rotated(String rawRefreshToken, AuthSession session) implements RefreshResult {

    @Override
    public String toString() {
      return "Rotated[session=%s]".formatted(session);
    }
  }

  /**
   * An honest retry within the rotation grace window; RFC 9700 reserves "replay" for the attack.
   */
  record GraceRetry(String rawRefreshToken, AuthSession session) implements RefreshResult {

    @Override
    public String toString() {
      return "GraceRetry[session=%s]".formatted(session);
    }
  }

  /** A grace-window retry whose derived successor has already been superseded. */
  record SupersededRetry(AuthSession session) implements RefreshResult {}
}
