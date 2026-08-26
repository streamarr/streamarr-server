package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthCookiePolicy;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.services.auth.AccessToken;
import com.streamarr.server.services.auth.TokenScope;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

@Tag("UnitTest")
@DisplayName("Auth Token Response Writer Tests")
class AuthTokenResponseWriterTest {

  private static final Instant EXPIRES_AT = Instant.parse("2026-08-26T16:00:00Z");
  private static final AccessToken ACCESS_TOKEN =
      AccessToken.builder()
          .value("access-token")
          .expiresAt(EXPIRES_AT)
          .scope(TokenScope.ACCOUNT)
          .build();
  private final AuthTokenResponseWriter writer = new AuthTokenResponseWriter(cookieWriter());

  @Test
  @DisplayName("Should return both credentials in the body when token delivery is requested")
  void shouldReturnBothCredentialsInBodyWhenTokenDeliveryIsRequested() {
    var response =
        writer.withRefreshToken(
            AuthTokenResponseWriter.RefreshResponse.builder()
                .status(HttpStatus.CREATED)
                .accessToken(ACCESS_TOKEN)
                .rawRefreshToken("refresh-token")
                .cookieMode(false)
                .build());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(response.getBody())
        .isEqualTo(
            new AuthTokensResponse(
                "access-token", EXPIRES_AT, TokenScope.ACCOUNT.claimValue(), "refresh-token"));
  }

  @Test
  @DisplayName("Should return both credentials as cookies when cookie delivery is requested")
  void shouldReturnBothCredentialsAsCookiesWhenCookieDeliveryIsRequested() {
    var response =
        writer.withRefreshToken(
            AuthTokenResponseWriter.RefreshResponse.builder()
                .status(HttpStatus.CREATED)
                .accessToken(ACCESS_TOKEN)
                .rawRefreshToken("refresh-token")
                .cookieMode(true)
                .build());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
        .hasSize(2)
        .anySatisfy(cookie -> assertThat(cookie).startsWith("streamarr_access=access-token"))
        .anySatisfy(cookie -> assertThat(cookie).startsWith("streamarr_refresh=refresh-token"));
    assertThat(response.getBody())
        .isEqualTo(new AuthTokensResponse(null, EXPIRES_AT, TokenScope.ACCOUNT.claimValue(), null));
  }

  @Test
  @DisplayName(
      "Should return only the access credential in the body when token delivery is requested")
  void shouldReturnOnlyAccessCredentialInBodyWhenTokenDeliveryIsRequested() {
    var response = writer.accessOnly(ACCESS_TOKEN, false);

    assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(response.getBody())
        .isEqualTo(
            new AuthTokensResponse(
                "access-token", EXPIRES_AT, TokenScope.ACCOUNT.claimValue(), null));
  }

  @Test
  @DisplayName(
      "Should return only the access credential as a cookie when cookie delivery is requested")
  void shouldReturnOnlyAccessCredentialAsCookieWhenCookieDeliveryIsRequested() {
    var response = writer.accessOnly(ACCESS_TOKEN, true);

    assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
        .singleElement()
        .asString()
        .startsWith("streamarr_access=access-token");
    assertThat(response.getBody())
        .isEqualTo(new AuthTokensResponse(null, EXPIRES_AT, TokenScope.ACCOUNT.claimValue(), null));
  }

  private static AuthCookieWriter cookieWriter() {
    return new AuthCookieWriter(
        AuthTokenProperties.builder()
            .accessTokenTtl(Duration.ofMinutes(10))
            .refreshTokenTtl(Duration.ofDays(30))
            .rotationGrace(Duration.ofSeconds(30))
            .build(),
        AuthCookiePolicy.SECURE);
  }
}
