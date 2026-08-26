package com.streamarr.server.controllers.auth;

import com.streamarr.server.services.auth.AccessToken;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthTokenResponseWriter {

  private final AuthCookieWriter cookieWriter;

  public ResponseEntity<AuthTokensResponse> withRefreshToken(RefreshResponse response) {
    var body = bodyOf(response.accessToken());
    if (!response.cookieMode()) {
      return ResponseEntity.status(response.status())
          .body(
              body.accessToken(response.accessToken().value())
                  .refreshToken(response.rawRefreshToken())
                  .build());
    }

    return ResponseEntity.status(response.status())
        .header(
            HttpHeaders.SET_COOKIE,
            cookieWriter.accessCookie(response.accessToken().value()).toString())
        .header(
            HttpHeaders.SET_COOKIE,
            cookieWriter.refreshCookie(response.rawRefreshToken()).toString())
        .body(body.build());
  }

  public ResponseEntity<AuthTokensResponse> accessOnly(
      AccessToken accessToken, boolean cookieMode) {
    var body = bodyOf(accessToken);
    if (!cookieMode) {
      return ResponseEntity.ok(body.accessToken(accessToken.value()).build());
    }

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookieWriter.accessCookie(accessToken.value()).toString())
        .body(body.build());
  }

  private static AuthTokensResponse.AuthTokensResponseBuilder bodyOf(AccessToken accessToken) {
    return AuthTokensResponse.builder()
        .accessTokenExpiresAt(accessToken.expiresAt())
        .scope(accessToken.scope().claimValue());
  }

  @Builder
  public record RefreshResponse(
      HttpStatus status, AccessToken accessToken, String rawRefreshToken, boolean cookieMode) {}
}
