package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("UnitTest")
@DisplayName("Streamarr Cookie Csrf Token Repository Tests")
class StreamarrCookieCsrfTokenRepositoryTest {

  private final StreamarrCookieCsrfTokenRepository repository =
      new StreamarrCookieCsrfTokenRepository(Duration.ofDays(30), AuthCookiePolicy.SECURE);

  @Test
  @DisplayName("Should reject a cookie lifetime when it is missing")
  void shouldRejectCookieLifetimeWhenMissing() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamarrCookieCsrfTokenRepository(null, AuthCookiePolicy.SECURE))
        .withMessage("cookieLifetime must be positive");
  }

  @ParameterizedTest(
      name = "Should reject a cookie lifetime of {0} seconds when it is non-positive")
  @DisplayName("Should reject a cookie lifetime when it is non-positive")
  @ValueSource(longs = {0, -1})
  void shouldRejectCookieLifetimeWhenNonPositive(long seconds) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new StreamarrCookieCsrfTokenRepository(
                    Duration.ofSeconds(seconds), AuthCookiePolicy.SECURE))
        .withMessage("cookieLifetime must be positive");
  }

  @ParameterizedTest
  @CsvSource({"SECURE, __Host-XSRF-TOKEN, true", "INSECURE_DEVELOPMENT, XSRF-TOKEN, false"})
  @DisplayName("Should expire the policy-specific csrf cookie when removing its token")
  void shouldExpirePolicySpecificCsrfCookieWhenRemovingItsToken(
      AuthCookiePolicy policy, String expectedCookieName, boolean expectedSecure) {
    var policyRepository = new StreamarrCookieCsrfTokenRepository(Duration.ofDays(30), policy);
    var response = new MockHttpServletResponse();

    policyRepository.saveToken(null, new MockHttpServletRequest(), response);

    var header = response.getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header)
        .startsWith(expectedCookieName + "=")
        .contains("Max-Age=0")
        .contains("Path=/")
        .doesNotContain("Domain=");
    assertThat(header.contains("; Secure")).isEqualTo(expectedSecure);
  }

  @Test
  @DisplayName("Should ignore the stale request cookie when its token was removed")
  void shouldIgnoreStaleRequestCookieWhenTokenWasRemoved() {
    var request = new MockHttpServletRequest();
    request.setCookies(new Cookie(AuthCookies.CSRF_COOKIE, "stale-token"));

    repository.saveToken(null, request, new MockHttpServletResponse());

    assertThat(repository.loadToken(request)).isNull();
  }

  @Test
  @DisplayName("Should generate a replacement when the cookie token is blank")
  void shouldGenerateReplacementWhenCookieTokenIsBlank() {
    var request = new MockHttpServletRequest();
    request.setCookies(new Cookie(AuthCookies.CSRF_COOKIE, " "));
    var response = new MockHttpServletResponse();

    var token = repository.loadDeferredToken(request, response).get();

    assertThat(token.getToken()).isNotBlank();
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
        .contains(AuthCookies.CSRF_COOKIE + "=" + token.getToken());
  }
}
