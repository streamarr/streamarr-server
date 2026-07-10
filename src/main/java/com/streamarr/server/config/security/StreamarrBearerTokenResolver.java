package com.streamarr.server.config.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Path-aware token resolution. The permitAll auth, health, and public-key endpoints resolve
 * nothing: the Path=/ access cookie rides every same-origin request, and an expired one would
 * otherwise 401 before the public endpoint runs. Everywhere else the Authorization header wins,
 * falling back to the access cookie.
 */
public class StreamarrBearerTokenResolver implements BearerTokenResolver {

  private static final String CARRIER_ATTRIBUTE =
      StreamarrBearerTokenResolver.class.getName() + ".carrier";

  private static final Set<String> UNAUTHENTICATED_PATHS =
      Set.of(
          "/api/auth/status",
          "/api/auth/setup",
          "/api/auth/login",
          "/api/auth/refresh",
          "/.well-known/jwks.json");
  private static final String HEALTH_PATH = "/actuator/health";

  private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

  @Override
  public String resolve(HttpServletRequest request) {
    var path = pathWithinApplication(request);
    if (UNAUTHENTICATED_PATHS.contains(path) || isHealthPath(path)) {
      return null;
    }

    // Stream paths resolve ONLY the ?t= parameter: a stale Path=/ access cookie must never 401
    // playback mid-movie, and even a valid one fails there (streams demand SCOPE_PLAYBACK).
    if (SecurityRequestMatchers.STREAM_PATHS.matches(request)) {
      var queryToken = request.getParameter("t");
      return queryToken != null && !queryToken.isBlank() ? queryToken : null;
    }

    var headerToken = headerResolver.resolve(request);
    if (headerToken != null) {
      request.setAttribute(CARRIER_ATTRIBUTE, CredentialCarrier.HEADER);
      return headerToken;
    }

    var cookieToken = accessCookieValue(request);
    if (cookieToken != null) {
      request.setAttribute(CARRIER_ATTRIBUTE, CredentialCarrier.COOKIE);
    }
    return cookieToken;
  }

  public static boolean usedAccessCookie(HttpServletRequest request) {
    return request.getAttribute(CARRIER_ATTRIBUTE) == CredentialCarrier.COOKIE;
  }

  private static String pathWithinApplication(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  private static boolean isHealthPath(String path) {
    return HEALTH_PATH.equals(path) || path.startsWith(HEALTH_PATH + "/");
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

  private enum CredentialCarrier {
    HEADER,
    COOKIE
  }
}
