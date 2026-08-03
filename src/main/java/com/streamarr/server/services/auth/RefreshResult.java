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

  /**
   * A grace-window replay whose derived successor has itself already rotated: reissuing it would
   * hand back a dead credential, so the caller receives a fresh access token only.
   */
  record SupersededRetry(AuthSession session) implements RefreshResult {}

  /**
   * A bearer client re-presenting the exact pair it persisted, whose proposed successor is still
   * this session's active token: the same successor is returned rather than rotated again, however
   * long ago the rotation committed.
   */
  record Recovered(String rawRefreshToken, AuthSession session) implements RefreshResult {

    @Override
    public String toString() {
      return "Recovered[session=%s]".formatted(session);
    }
  }
}
