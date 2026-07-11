package com.streamarr.server.repositories.auth;

import java.util.UUID;

public record AccountCredential(UUID accountId, String passwordHash) {

  @Override
  public String toString() {
    return "AccountCredential[accountId=%s, credential=REDACTED]".formatted(accountId);
  }
}
