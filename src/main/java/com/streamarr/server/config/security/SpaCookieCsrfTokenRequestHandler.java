package com.streamarr.server.config.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * The documented SPA shape: BREACH-protected (Xor) rendering with eager realisation so the active
 * readable CSRF cookie is always written, and header-only resolution — the page reads the cookie
 * and echoes it as X-XSRF-TOKEN.
 */
final class SpaCookieCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

  private final XorCsrfTokenRequestAttributeHandler xorHandler =
      new XorCsrfTokenRequestAttributeHandler();

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
    xorHandler.handle(request, response, csrfToken);
    // Realise the deferred token so the cookie repository writes the active CSRF cookie for the
    // SPA.
    csrfToken.get();
  }

  @Override
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
    var headerValue = request.getHeader(csrfToken.getHeaderName());
    return StringUtils.hasText(headerValue) ? headerValue : null;
  }
}
