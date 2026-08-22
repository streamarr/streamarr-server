package com.streamarr.server.services.auth;

import java.util.UUID;

public record LoggedOutSession(UUID accountId, UUID registrationId) {

  public boolean deviceBound() {
    return registrationId != null;
  }
}
