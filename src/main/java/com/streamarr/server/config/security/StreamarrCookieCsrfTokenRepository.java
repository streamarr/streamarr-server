package com.streamarr.server.config.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

final class StreamarrCookieCsrfTokenRepository implements CsrfTokenRepository {

  static final String HEADER_NAME = "X-XSRF-TOKEN";

  private static final String PARAMETER_NAME = "_csrf";
  private static final String TOKEN_REMOVED_ATTRIBUTE =
      StreamarrCookieCsrfTokenRepository.class.getName() + ".TOKEN_REMOVED";

  private final Duration cookieLifetime;

  StreamarrCookieCsrfTokenRepository(Duration cookieLifetime) {
    this.cookieLifetime = cookieLifetime;
  }

  @Override
  public CsrfToken generateToken(HttpServletRequest request) {
    return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, UUID.randomUUID().toString());
  }

  // The anti-CSRF nonce must be script-readable so the SPA can echo it in X-XSRF-TOKEN. It is not
  // an authentication credential; the access and refresh cookies remain HttpOnly.
  @Override
  @SuppressWarnings("java:S3330")
  public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
    var removingToken = token == null;
    var cookie =
        ResponseCookie.from(AuthCookies.CSRF_COOKIE, removingToken ? "" : token.getToken())
            .secure(true)
            .httpOnly(false)
            .sameSite("Lax")
            .path("/")
            .maxAge(removingToken ? Duration.ZERO : cookieLifetime)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    request.setAttribute(TOKEN_REMOVED_ATTRIBUTE, removingToken ? Boolean.TRUE : null);
  }

  @Override
  public CsrfToken loadToken(HttpServletRequest request) {
    if (Boolean.TRUE.equals(request.getAttribute(TOKEN_REMOVED_ATTRIBUTE))) {
      return null;
    }
    var cookie = WebUtils.getCookie(request, AuthCookies.CSRF_COOKIE);
    if (cookie == null || !StringUtils.hasText(cookie.getValue())) {
      return null;
    }
    return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, cookie.getValue());
  }

  @Override
  public DeferredCsrfToken loadDeferredToken(
      HttpServletRequest request, HttpServletResponse response) {
    return new DeferredCsrfToken() {
      private CsrfToken token;
      private boolean generated;

      @Override
      public CsrfToken get() {
        initialize();
        return token;
      }

      @Override
      public boolean isGenerated() {
        initialize();
        return generated;
      }

      private void initialize() {
        if (token != null) {
          return;
        }
        token = loadToken(request);
        generated = token == null;
        if (generated) {
          token = generateToken(request);
        }
        saveToken(token, request, response);
      }
    };
  }
}
