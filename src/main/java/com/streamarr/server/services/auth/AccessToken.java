package com.streamarr.server.services.auth;

import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record AccessToken(
    @NonNull String value, @NonNull Instant expiresAt, @NonNull TokenScope scope) {

  public static class AccessTokenBuilder {

    @Override
    public String toString() {
      return "AccessTokenBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "AccessToken[value=[REDACTED], expiresAt=%s, scope=%s]".formatted(expiresAt, scope);
  }
}
