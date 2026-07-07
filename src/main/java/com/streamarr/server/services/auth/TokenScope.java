package com.streamarr.server.services.auth;

import java.util.Locale;

public enum TokenScope {
  ACCOUNT,
  HOUSEHOLD,
  PROFILE;

  public String claimValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
