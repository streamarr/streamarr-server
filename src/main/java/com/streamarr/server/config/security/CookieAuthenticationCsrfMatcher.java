package com.streamarr.server.config.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * CSRF protection applies only to requests that could ride ambient cookie credentials: an unsafe
 * method, no bearer Authorization credential, and any Streamarr auth cookie present. Every ambient
 * auth cookie that can authenticate requires CSRF protection. SameSite=Strict already blocks
 * cross-site sends; CSRF stays correct independently as the second layer.
 */
public class CookieAuthenticationCsrfMatcher implements RequestMatcher {

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

  @Override
  public boolean matches(HttpServletRequest request) {
    if (SAFE_METHODS.contains(request.getMethod())) {
      return false;
    }
    if (hasBearerAuthorization(request)) {
      return false;
    }
    return hasStreamarrAuthCookie(request);
  }

  private static boolean hasBearerAuthorization(HttpServletRequest request) {
    var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    var prefix = "Bearer ";
    return authorization != null
        && authorization.regionMatches(true, 0, prefix, 0, prefix.length())
        && !authorization.substring(prefix.length()).isBlank();
  }

  private static boolean hasStreamarrAuthCookie(HttpServletRequest request) {
    var cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }

    return Arrays.stream(cookies)
        .anyMatch(
            cookie ->
                AuthCookies.ACCESS_COOKIE.equals(cookie.getName())
                    || AuthCookies.REFRESH_COOKIE.equals(cookie.getName()));
  }
}
