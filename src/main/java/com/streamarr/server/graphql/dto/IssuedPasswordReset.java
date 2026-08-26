package com.streamarr.server.graphql.dto;

import java.util.UUID;
import lombok.Builder;

/** The code appears here once and never again. */
@Builder
public record IssuedPasswordReset(UUID accountId, String code, String expiresAt) {

  public static class IssuedPasswordResetBuilder {

    @Override
    public String toString() {
      return "IssuedPasswordResetBuilder[accountId=%s, code=REDACTED, expiresAt=%s]"
          .formatted(accountId, expiresAt);
    }
  }

  @Override
  public String toString() {
    return "IssuedPasswordReset[accountId=%s, code=REDACTED, expiresAt=%s]"
        .formatted(accountId, expiresAt);
  }
}
