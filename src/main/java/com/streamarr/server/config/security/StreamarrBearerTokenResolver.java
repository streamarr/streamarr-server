package com.streamarr.server.config.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Path-aware token resolution. The permitAll auth endpoints resolve nothing: the Path=/ access
 * cookie rides every same-origin request, and an expired one would otherwise 401 the refresh before
 * the controller runs — deadlocking cookie-mode renewal into logout. Everywhere else the
 * Authorization header wins, falling back to the access cookie.
 */
public class StreamarrBearerTokenResolver implements BearerTokenResolver {

  private static final Set<String> UNAUTHENTICATED_AUTH_PATHS =
      Set.of("/api/auth/status", "/api/auth/setup", "/api/auth/login", "/api/auth/refresh");

  private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

  @Override
  public String resolve(HttpServletRequest request) {
    if (UNAUTHENTICATED_AUTH_PATHS.contains(pathWithinApplication(request))) {
      return null;
    }

    var headerToken = headerResolver.resolve(request);
    if (headerToken != null) {
      return headerToken;
    }

    return accessCookieValue(request);
  }

  private static String pathWithinApplication(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  private static String accessCookieValue(HttpServletRequest request) {
    var cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }

    return Arrays.stream(cookies)
        .filter(cookie -> AuthCookies.ACCESS_COOKIE.equals(cookie.getName()))
        .map(Cookie::getValue)
        .filter(value -> !value.isBlank())
        .findFirst()
        .orElse(null);
  }
}
