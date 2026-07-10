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

  /** An honest grace replay that recovers the still-active rotation successor. */
  record Replayed(String rawRefreshToken, AuthSession session) implements RefreshResult {

    @Override
    public String toString() {
      return "Replayed[session=%s]".formatted(session);
    }
  }

  /** A grace replay whose derived successor has already been superseded. */
  record SupersededReplay(AuthSession session) implements RefreshResult {

    @Override
    public String toString() {
      return "SupersededReplay[session=%s]".formatted(session);
    }
  }
}
