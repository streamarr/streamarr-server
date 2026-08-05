package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("UnitTest")
@DisplayName("Streamarr Cookie Csrf Matcher Tests")
class StreamarrCookieCsrfMatcherTest {

  private final StreamarrCookieCsrfMatcher matcher = new StreamarrCookieCsrfMatcher();

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

  @Test
  @DisplayName("Should require csrf when only the csrf cookie rides a login")
  void shouldRequireCsrfWhenOnlyTheCsrfCookieRidesALogin() {
    // Login carries its credential in the body, so no auth cookie is present yet — but the XSRF
    // cookie proves a browser that has already talked to this origin, which is exactly the
    // login-CSRF victim.
    var request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.setCookies(new Cookie(AuthCookies.CSRF_COOKIE, "csrf-value"));

    assertThat(matcher.matches(request)).isTrue();
  }

  @Test
  @DisplayName("Should require csrf when a stale access cookie rides a login")
  void shouldRequireCsrfWhenStaleAccessCookieRidesALogin() {
    var request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "stale-access-token"));

    assertThat(matcher.matches(request)).isTrue();
  }

  @Test
  @DisplayName("Should not require csrf when no cookies ride the request")
  void shouldNotRequireCsrfWhenNoCookiesRideTheRequest() {
    // The native/TV shape: body credentials, no cookie jar for this origin, nothing ambient to
    // forge. Device pairing polls arrive this way too.
    var request = new MockHttpServletRequest("POST", "/api/auth/login");

    assertThat(matcher.matches(request)).isFalse();
  }

  @Test
  @DisplayName("Should not require csrf when only a foreign cookie rides the request")
  void shouldNotRequireCsrfWhenOnlyForeignCookieRidesTheRequest() {
    // Only Streamarr-issued cookies are evidence of a browser session with this application.
    var request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.setCookies(new Cookie("some_other_app", "irrelevant"));

    assertThat(matcher.matches(request)).isFalse();
  }

  @Test
  @DisplayName(
      "Should not require csrf when an accepted bearer credential accompanies streamarr cookies")
  void shouldNotRequireCsrfWhenBearerCredentialAccompaniesStreamarrCookies() {
    var request = new MockHttpServletRequest("POST", "/graphql");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer a-real-looking-token");
    request.setCookies(new Cookie(AuthCookies.CSRF_COOKIE, "csrf-value"));

    assertThat(matcher.matches(request)).isFalse();
  }

  @Test
  @DisplayName(
      "Should not require csrf when an accepted bearer credential uses a differently cased scheme")
  void shouldNotRequireCsrfWhenAcceptedBearerCredentialUsesDifferentlyCasedScheme() {
    var request = new MockHttpServletRequest("POST", "/graphql");
    request.addHeader(HttpHeaders.AUTHORIZATION, "bearer a-real-looking-token");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "access-value"));

    assertThat(matcher.matches(request)).isFalse();
  }

  @Test
  @DisplayName("Should require csrf when a bearer header is ignored on login")
  void shouldRequireCsrfWhenBearerHeaderIsIgnoredOnLogin() {
    var request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ignored-token");
    request.setCookies(new Cookie(AuthCookies.CSRF_COOKIE, "csrf-value"));

    assertThat(matcher.matches(request)).isTrue();
  }

  @Test
  @DisplayName("Should require csrf when bearer header rides login with matrix parameters")
  void shouldRequireCsrfWhenBearerHeaderRidesLoginWithMatrixParameters() {
    var request = new MockHttpServletRequest("POST", "/api/auth/login;source=test");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ignored-token");
    request.setCookies(new Cookie(AuthCookies.CSRF_COOKIE, "csrf-value"));

    assertThat(matcher.matches(request)).isTrue();
  }

  @Test
  @DisplayName("Should not require csrf when the method is safe despite streamarr cookies")
  void shouldNotRequireCsrfWhenMethodIsSafeDespiteStreamarrCookies() {
    var request = new MockHttpServletRequest("GET", "/api/auth/status");
    request.setCookies(new Cookie(AuthCookies.CSRF_COOKIE, "csrf-value"));

    assertThat(matcher.matches(request)).isFalse();
  }

  @ParameterizedTest(name = "Should require csrf when method {0} is unsafe")
  @DisplayName("Should require csrf when the method is unsafe")
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  void shouldRequireCsrfWhenMethodIsUnsafe(String method) {
    var request = new MockHttpServletRequest(method, "/graphql");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "access-value"));

    assertThat(matcher.matches(request)).isTrue();
  }

  @ParameterizedTest(name = "Should not require csrf when method {0} is safe")
  @DisplayName("Should not require csrf when the method is safe")
  @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE"})
  void shouldNotRequireCsrfWhenMethodIsSafe(String method) {
    var request = new MockHttpServletRequest(method, "/graphql");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "access-value"));

    assertThat(matcher.matches(request)).isFalse();
  }
}
