package com.streamarr.server.config.security;

public final class AuthCookies {

  public static final String ACCESS_COOKIE = "streamarr_access";
  public static final String REFRESH_COOKIE = "streamarr_refresh";
  public static final String REFRESH_PATH = "/api/auth/refresh";

  private AuthCookies() {}
}
