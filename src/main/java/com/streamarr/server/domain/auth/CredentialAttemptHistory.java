package com.streamarr.server.domain.auth;

import java.time.Instant;
import java.util.List;
import lombok.NonNull;

/**
 * What a limited target has on record when an attempt asks for admission: every completed failure
 * after its latest success that could still anchor a lockout, oldest first, and the instant each
 * fresh pending reservation would be abandoned.
 */
public record CredentialAttemptHistory(
    @NonNull List<Instant> failures, @NonNull List<Instant> pendingExpiries) {

  public CredentialAttemptHistory {
    failures = List.copyOf(failures);
    pendingExpiries = List.copyOf(pendingExpiries);
  }
}
