package com.streamarr.server.services.auth;

import java.util.Locale;

public enum TokenScope {
  ACCOUNT,
  HOUSEHOLD,
  PROFILE,
  /** Authorizes only stream paths; deliberately outside the scope hierarchy. */
  PLAYBACK;

  public String claimValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
