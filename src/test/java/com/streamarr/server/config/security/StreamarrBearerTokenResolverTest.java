package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Tag("UnitTest")
@DisplayName("Streamarr Bearer Token Resolver Tests")
class StreamarrBearerTokenResolverTest {

  private final StreamarrBearerTokenResolver resolver = new StreamarrBearerTokenResolver();

  @Test
  @DisplayName("Should prefer authorization header when both header and cookie present")
  void shouldPreferAuthorizationHeaderWhenBothHeaderAndCookiePresent() {
    var request = requestFor("/api/images/some-id");
    request.addHeader("Authorization", "Bearer header-token");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "cookie-token"));

    assertThat(resolver.resolve(request)).isEqualTo("header-token");
  }

  @Test
  @DisplayName("Should fall back to access cookie when header absent")
  void shouldFallBackToAccessCookieWhenHeaderAbsent() {
    var request = requestFor("/api/images/some-id");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "cookie-token"));

    assertThat(resolver.resolve(request)).isEqualTo("cookie-token");
  }

  @Test
  @DisplayName("Should resolve nothing when access cookie blank")
  void shouldResolveNothingWhenAccessCookieBlank() {
    var request = requestFor("/api/images/some-id");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, ""));

    assertThat(resolver.resolve(request)).isNull();
  }

  @Test
  @DisplayName("Should resolve nothing when request carries no cookies")
  void shouldResolveNothingWhenRequestCarriesNoCookies() {
    assertThat(resolver.resolve(requestFor("/api/images/some-id"))).isNull();
  }

  @Test
  @DisplayName("Should resolve playback query token when application has context path")
  void shouldResolvePlaybackQueryTokenWhenApplicationHasContextPath() {
    var request = requestFor("/streamarr/api/stream/some-id/master.m3u8");
    request.setContextPath("/streamarr");
    request.setServletPath("/api/stream/some-id/master.m3u8");
    request.setParameter("t", "playback-token");
    var streamMatcher = PathPatternRequestMatcher.withDefaults().matcher("/api/stream/**");

    assertThat(streamMatcher.matches(request)).isTrue();
    assertThat(resolver.resolve(request)).isEqualTo("playback-token");
  }

  @ParameterizedTest(name = "Should suppress bearer resolution on {0}")
  @ValueSource(
      strings = {"/api/auth/status", "/api/auth/setup", "/api/auth/login", "/api/auth/refresh"})
  void shouldSuppressBearerResolutionOnUnauthenticatedAuthPath(String uri) {
    var request = requestFor(uri);
    request.addHeader("Authorization", "Bearer header-token");
    request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "cookie-token"));

    assertThat(resolver.resolve(request)).isNull();
  }

  private static MockHttpServletRequest requestFor(String uri) {
    var request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    return request;
  }
}
