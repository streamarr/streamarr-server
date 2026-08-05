package com.streamarr.server.config.security;

public final class AuthCookies {

  public static final String ACCESS_COOKIE = "streamarr_access";
  public static final String REFRESH_COOKIE = "streamarr_refresh";

  // The double-submit cookie, not a credential: the page reads it and echoes X-XSRF-TOKEN. Secure
  // deployments use the host-bound name; explicitly insecure development cannot use __Host-.
  // The web counterpart is src/auth/csrf.ts in streamarr-web.
  public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
  public static final String INSECURE_DEVELOPMENT_CSRF_COOKIE = "XSRF-TOKEN";
  public static final String CSRF_HEADER = "X-XSRF-TOKEN";

  // Cookie scope is an application-owned route, not an environment-specific URI.
  @SuppressWarnings("java:S1075")
  public static final String REFRESH_PATH = "/api/auth/refresh";

  private AuthCookies() {}
}
