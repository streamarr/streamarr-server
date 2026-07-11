package com.streamarr.server.config.security;

public final class AuthCookies {

  public static final String ACCESS_COOKIE = "streamarr_access";
  public static final String REFRESH_COOKIE = "streamarr_refresh";

  // Cookie scope is an application-owned route, not an environment-specific URI.
  @SuppressWarnings("java:S1075")
  public static final String REFRESH_PATH = "/api/auth/refresh";

  private AuthCookies() {}
}
