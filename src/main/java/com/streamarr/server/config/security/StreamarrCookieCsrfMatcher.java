package com.streamarr.server.config.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * CSRF protection applies to any unsafe request from a browser that already holds a Streamarr
 * cookie and presents no bearer credential that the resolver can consume on that route.
 *
 * <p>Matching on the auth cookies alone protected only requests that ride an ambient credential,
 * which left login uncovered: login carries its credential in the body, so a fresh browser reaches
 * it with no auth cookie and the response mints the session. That is login CSRF — a victim silently
 * signed into the attacker's account, who then pairs a TV to it.
 *
 * <p>The __Host-XSRF-TOKEN cookie closes that gap for returning browsers: the filter writes it on
 * the SPA's first request, so any browser that has talked to this origin carries one before it can
 * POST a login. First-contact safety also relies on JSON-only auth mutations and the absence of
 * hostile CORS grants. A native client carries no cookies — the Apple clients disable cookie
 * storage — so bearer-mode login and the planned device-pairing flow stay reachable with no CSRF
 * token. An unrecognised cookie-keeping client fails closed with a CSRF 403.
 */
final class StreamarrCookieCsrfMatcher implements RequestMatcher {

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

  @Override
  public boolean matches(HttpServletRequest request) {
    if (SAFE_METHODS.contains(request.getMethod())) {
      return false;
    }
    if (hasBearerAuthorization(request)
        && StreamarrBearerTokenResolver.acceptsAuthorizationHeader(request)) {
      return false;
    }
    return hasStreamarrCookie(request);
  }

  private static boolean hasBearerAuthorization(HttpServletRequest request) {
    var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    var prefix = "Bearer ";
    return authorization != null
        && authorization.regionMatches(true, 0, prefix, 0, prefix.length())
        && !authorization.substring(prefix.length()).isBlank();
  }

  private static boolean hasStreamarrCookie(HttpServletRequest request) {
    var cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }

    return Arrays.stream(cookies).anyMatch(cookie -> AuthCookies.ALL.contains(cookie.getName()));
  }
}
