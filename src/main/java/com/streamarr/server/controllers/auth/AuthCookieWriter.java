package com.streamarr.server.controllers.auth;

import com.streamarr.server.config.security.AuthCookies;
import com.streamarr.server.config.security.AuthTokenProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthCookieWriter {

  public static final String ACCESS_COOKIE = AuthCookies.ACCESS_COOKIE;
  public static final String REFRESH_COOKIE = AuthCookies.REFRESH_COOKIE;
  public static final String REFRESH_PATH = AuthCookies.REFRESH_PATH;

  private final AuthTokenProperties properties;

  /**
   * The access cookie outlives its token by design: the browser must keep sending the expired JWT
   * so the server can answer EXPIRED_TOKEN and the client's refresh path can fire. Both cookies
   * live for the refresh-token lifetime.
   */
  public ResponseCookie accessCookie(String token) {
    return withDefaults(ACCESS_COOKIE, token, "/", properties.refreshTokenTtl());
  }

  public ResponseCookie refreshCookie(String rawRefreshToken) {
    return withDefaults(
        REFRESH_COOKIE, rawRefreshToken, REFRESH_PATH, properties.refreshTokenTtl());
  }

  public ResponseCookie expiredAccessCookie() {
    return withDefaults(ACCESS_COOKIE, "", "/", Duration.ZERO);
  }

  public ResponseCookie expiredRefreshCookie() {
    return withDefaults(REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO);
  }

  private static ResponseCookie withDefaults(
      String name, String value, String path, Duration maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(path)
        .maxAge(maxAge)
        .build();
  }
}
