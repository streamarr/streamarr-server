package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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

  private static MockHttpServletRequest requestFor(String uri) {
    var request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    return request;
  }
}
