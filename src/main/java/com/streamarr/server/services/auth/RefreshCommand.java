package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import lombok.Builder;

/** One refresh attempt: the credential presented, and how the caller wants it renewed. */
@Builder
public record RefreshCommand(String refreshToken) {

  public RefreshCommand {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new InvalidRefreshTokenException();
    }
  }

  public static class RefreshCommandBuilder {

    @Override
    public String toString() {
      return "RefreshCommandBuilder[refreshToken=REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "RefreshCommand[refreshToken=REDACTED]";
  }
}
