package com.streamarr.server.config.security;

public final class AuthCookies {

  public static final String ACCESS_COOKIE = "streamarr_access";
  public static final String REFRESH_COOKIE = "streamarr_refresh";

  // The double-submit cookie, not a credential: the page reads it and echoes X-XSRF-TOKEN. Named
  // here because the CSRF filter writes it and the CSRF matcher reads it, and the two must agree.
  public static final String CSRF_COOKIE = "XSRF-TOKEN";

  // Cookie scope is an application-owned route, not an environment-specific URI.
  @SuppressWarnings("java:S1075")
  public static final String REFRESH_PATH = "/api/auth/refresh";

  private AuthCookies() {}
}
