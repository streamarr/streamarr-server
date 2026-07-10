package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import lombok.Builder;

@Builder
public record LoginResult(UserAccount account, AuthSession session, String rawRefreshToken) {

  public static class LoginResultBuilder {

    @Override
    public String toString() {
      return "LoginResultBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "LoginResult[account=%s, session=%s, rawRefreshToken=[REDACTED]]"
        .formatted(account, session);
  }
}
