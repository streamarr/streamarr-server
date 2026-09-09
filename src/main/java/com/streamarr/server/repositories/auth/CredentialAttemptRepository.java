package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import java.time.Instant;
import java.util.function.Supplier;

public interface CredentialAttemptRepository {

  CredentialAttemptAdmission reserve(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy);

  void complete(CredentialAttemptReservation reservation, CredentialAttemptResult result);

  /**
   * Commits a database mutation and the successful attempt together under the target lock. The
   * mutation must not perform credential hashing or external I/O.
   */
  <T> T completeWith(CredentialAttemptReservation reservation, Supplier<T> mutation);

  int deleteAttemptedBefore(Instant cutoff);
}
