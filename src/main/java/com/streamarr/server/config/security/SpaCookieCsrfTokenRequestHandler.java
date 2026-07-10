package com.streamarr.server.config.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * The documented SPA shape: BREACH-protected (Xor) rendering with eager realisation so the readable
 * XSRF-TOKEN cookie is always written, and plain resolution for header-supplied tokens — the page
 * reads the cookie and echoes it as X-XSRF-TOKEN.
 */
public class SpaCookieCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

  private final XorCsrfTokenRequestAttributeHandler xorHandler =
      new XorCsrfTokenRequestAttributeHandler();
  private final CsrfTokenRequestAttributeHandler plainHandler =
      new CsrfTokenRequestAttributeHandler();

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
    xorHandler.handle(request, response, csrfToken);
    csrfToken.get();
  }

  @Override
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
    var headerValue = request.getHeader(csrfToken.getHeaderName());
    var resolver = StringUtils.hasText(headerValue) ? plainHandler : xorHandler;
    return resolver.resolveCsrfTokenValue(request, csrfToken);
  }
}
