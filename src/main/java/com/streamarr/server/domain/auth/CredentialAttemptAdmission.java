package com.streamarr.server.domain.auth;

import java.time.Duration;
import lombok.NonNull;

public sealed interface CredentialAttemptAdmission {

  record Reserved(@NonNull CredentialAttemptReservation reservation)
      implements CredentialAttemptAdmission {}

  record Blocked(@NonNull Duration retryAfter) implements CredentialAttemptAdmission {

    public Blocked {
      if (retryAfter.isZero() || retryAfter.isNegative()) {
        throw new IllegalArgumentException("retryAfter must be positive");
      }
    }
  }
}
