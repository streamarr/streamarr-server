package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;

public sealed interface RefreshResult {

  AuthSession session();

  /** A genuine rotation: the caller receives the one new refresh token for this session. */
  record Rotated(String rawRefreshToken, AuthSession session) implements RefreshResult {}

  /**
   * An honest race inside the rotation grace window: the caller gets a fresh access token but no
   * refresh token — exactly one rotation happens per grace episode.
   */
  record GraceReplay(AuthSession session) implements RefreshResult {}
}
