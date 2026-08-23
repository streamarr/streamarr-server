package com.streamarr.server.services.auth;

import java.util.Locale;

/** ADR 0024 token scopes: Account (picker), Profile (watching as), Playback (stream paths only). */
public enum TokenScope {
  ACCOUNT,
  PROFILE,
  PLAYBACK;

  public String claimValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public String authority() {
    return "SCOPE_" + name();
  }
}
