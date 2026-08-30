package com.streamarr.server.services.auth;

import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

public record LoggedOutSession(UUID accountId, @NonNull Optional<UUID> registrationId) {

  public boolean deviceBound() {
    return registrationId.isPresent();
  }
}
