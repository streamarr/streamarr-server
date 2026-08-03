package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthCookieSecurity;
import com.streamarr.server.config.security.AuthTokenProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ResponseCookie;

@Tag("UnitTest")
@DisplayName("Auth Cookie Writer Tests")
class AuthCookieWriterTest {

  @Test
  @DisplayName("Should mark every cookie Secure when cookie security is secure")
  void shouldMarkEveryCookieSecureWhenCookieSecurityIsSecure() {
    var cookies = allCookiesFrom(writerWith(AuthCookieSecurity.SECURE));

    assertThat(cookies).allSatisfy(cookie -> assertThat(cookie.isSecure()).isTrue());
  }

  @Test
  @DisplayName("Should omit Secure from every cookie when cookie security is insecure development")
  void shouldOmitSecureFromEveryCookieWhenCookieSecurityIsInsecureDevelopment() {
    // Safari refuses to store or send a Secure cookie over http://localhost, so dropping the
    // attribute is the only way a cookie-mode session survives there.
    var cookies = allCookiesFrom(writerWith(AuthCookieSecurity.INSECURE_DEVELOPMENT));

    assertThat(cookies).allSatisfy(cookie -> assertThat(cookie.isSecure()).isFalse());
  }

  @ParameterizedTest
  @EnumSource(AuthCookieSecurity.class)
  @DisplayName("Should keep httpOnly and SameSite=Strict when cookie security varies")
  void shouldKeepHttpOnlyAndStrictSameSiteWhenCookieSecurityVaries(AuthCookieSecurity security) {
    // The development relaxation covers Secure and nothing else: ADR 0016 rests on browsers never
    // touching tokens from script, and the service worker design assumes unreadable cookies.
    var cookies = allCookiesFrom(writerWith(security));

    assertThat(cookies)
        .allSatisfy(
            cookie -> {
              assertThat(cookie.isHttpOnly()).isTrue();
              assertThat(cookie.getSameSite()).isEqualTo("Strict");
            });
  }

  private static List<ResponseCookie> allCookiesFrom(AuthCookieWriter writer) {
    return List.of(
        writer.accessCookie("access-token"),
        writer.refreshCookie("refresh-token"),
        writer.expiredAccessCookie(),
        writer.expiredRefreshCookie());
  }

  private static AuthCookieWriter writerWith(AuthCookieSecurity security) {
    return new AuthCookieWriter(
        AuthTokenProperties.builder()
            .accessTokenTtl(Duration.ofMinutes(10))
            .refreshTokenTtl(Duration.ofDays(30))
            .rotationGrace(Duration.ofSeconds(30))
            .build(),
        security);
  }
}
