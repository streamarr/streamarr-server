package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

@Tag("UnitTest")
@DisplayName("Spa Cookie Csrf Token Request Handler Tests")
class SpaCookieCsrfTokenRequestHandlerTest {

  private final SpaCookieCsrfTokenRequestHandler handler = new SpaCookieCsrfTokenRequestHandler();

  @Test
  @DisplayName("Should ignore a csrf token supplied only as a request parameter")
  void shouldIgnoreCsrfTokenSuppliedOnlyAsRequestParameter() {
    var token = new DefaultCsrfToken(AuthCookies.CSRF_HEADER, "_csrf", "raw-token");
    var request = new MockHttpServletRequest();
    new XorCsrfTokenRequestAttributeHandler()
        .handle(request, new MockHttpServletResponse(), () -> token);
    var maskedToken = ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
    request.setParameter(token.getParameterName(), maskedToken);

    assertThat(handler.resolveCsrfTokenValue(request, token)).isNull();
  }
}
