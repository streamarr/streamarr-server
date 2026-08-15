package com.streamarr.server.services.auth;

import java.util.UUID;

record PasswordReauthentication(UUID accountId) {

  @Override
  public String toString() {
    return "PasswordReauthentication[accountId=%s]".formatted(accountId);
  }
}
