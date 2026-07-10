package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("UnitTest")
@DisplayName("Cookie Authentication Csrf Matcher Tests")
class CookieAuthenticationCsrfMatcherTest {

  private final CookieAuthenticationCsrfMatcher matcher = new CookieAuthenticationCsrfMatcher();

  @Test
  @DisplayName("Should require csrf when only refresh cookie rides the request")
  void shouldRequireCsrfWhenOnlyRefreshCookieRidesTheRequest() {
    var request = new MockHttpServletRequest("POST", "/api/auth/refresh");
    request.setCookies(new Cookie(AuthCookies.REFRESH_COOKIE, "refresh-value"));

    assertThat(matcher.matches(request)).isTrue();
  }

  @Test
  @DisplayName(
      "Should require csrf when a non-bearer authorization header accompanies auth cookies")
  void shouldRequireCsrfWhenNonBearerAuthorizationHeaderAccompaniesAuthCookies() {
    var request = new MockHttpServletRequest("POST", "/graphql");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Basic irrelevant");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "access-value"));

    assertThat(matcher.matches(request)).isTrue();
  }
}
