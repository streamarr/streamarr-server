package com.streamarr.server.graphql.dto;

import java.util.UUID;

/** The code appears here once and never again. */
public record IssuedPasswordReset(UUID accountId, String code, String expiresAt) {

  @Override
  public String toString() {
    return "IssuedPasswordReset[accountId=%s, code=REDACTED, expiresAt=%s]"
        .formatted(accountId, expiresAt);
  }
}
