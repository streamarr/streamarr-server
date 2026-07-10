package com.streamarr.server.controllers.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthTokensResponse(
    String accessToken, Instant accessTokenExpiresAt, String scope, String refreshToken) {

  public static class AuthTokensResponseBuilder {

    @Override
    public String toString() {
      return "AuthTokensResponseBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "AuthTokensResponse[accessToken=REDACTED, accessTokenExpiresAt=%s, scope=%s,"
        + " refreshToken=REDACTED]".formatted(accessTokenExpiresAt, scope);
  }
}
