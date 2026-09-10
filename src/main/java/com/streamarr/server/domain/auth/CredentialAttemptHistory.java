package com.streamarr.server.domain.auth;

import java.time.Instant;
import java.util.List;
import lombok.NonNull;

/**
 * Completed failures relevant to admission, ordered oldest first, and expiry times for pending
 * reservations. When the policy resets on success, failures completed at or before the latest
 * success are excluded.
 */
public record CredentialAttemptHistory(
    @NonNull List<Instant> failures, @NonNull List<Instant> pendingExpiries) {

  public CredentialAttemptHistory {
    failures = List.copyOf(failures);
    pendingExpiries = List.copyOf(pendingExpiries);
  }
}
