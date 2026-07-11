package com.streamarr.server.services.auth;

import java.time.Instant;
import java.util.Objects;
import lombok.Builder;

@Builder
public record AccessToken(String value, Instant expiresAt, TokenScope scope) {

  public static class AccessTokenBuilder {

    @Override
    public String toString() {
      return "AccessTokenBuilder[REDACTED]";
    }
  }

  public AccessToken {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(scope, "scope");
  }

  @Override
  public String toString() {
    return "AccessToken[value=[REDACTED], expiresAt=%s, scope=%s]".formatted(expiresAt, scope);
  }
}
