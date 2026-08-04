package com.streamarr.server.config.security;

import java.util.Set;

public final class AuthCookies {

  public static final String ACCESS_COOKIE = "streamarr_access";
  public static final String REFRESH_COOKIE = "streamarr_refresh";

  // The double-submit cookie, not a credential: the page reads it and echoes X-XSRF-TOKEN. Named
  // here because the CSRF filter writes it and the CSRF matcher reads it, and the two must agree.
  // The web counterpart is src/auth/csrf.ts in streamarr-web.
  public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
  public static final String CSRF_HEADER = "X-XSRF-TOKEN";

  static final Set<String> ALL = Set.of(ACCESS_COOKIE, REFRESH_COOKIE, CSRF_COOKIE);

  // Cookie scope is an application-owned route, not an environment-specific URI.
  @SuppressWarnings("java:S1075")
  public static final String REFRESH_PATH = "/api/auth/refresh";

  private AuthCookies() {}
}
