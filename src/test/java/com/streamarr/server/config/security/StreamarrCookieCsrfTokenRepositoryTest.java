package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("UnitTest")
@DisplayName("Streamarr Cookie Csrf Token Repository Tests")
class StreamarrCookieCsrfTokenRepositoryTest {

  private final StreamarrCookieCsrfTokenRepository repository =
      new StreamarrCookieCsrfTokenRepository(Duration.ofDays(30));

  @Test
  @DisplayName("Should reject a missing or non-positive cookie lifetime")
  void shouldRejectMissingOrNonPositiveCookieLifetime() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamarrCookieCsrfTokenRepository(null))
        .withMessage("cookieLifetime must be positive");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamarrCookieCsrfTokenRepository(Duration.ZERO))
        .withMessage("cookieLifetime must be positive");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StreamarrCookieCsrfTokenRepository(Duration.ofSeconds(-1)))
        .withMessage("cookieLifetime must be positive");
  }

  @Test
  @DisplayName("Should expire the csrf cookie immediately when removing its token")
  void shouldExpireCsrfCookieImmediatelyWhenRemovingItsToken() {
    var response = new MockHttpServletResponse();

    repository.saveToken(null, new MockHttpServletRequest(), response);

    assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
        .contains(AuthCookies.CSRF_COOKIE + "=")
        .contains("Max-Age=0");
  }
}
