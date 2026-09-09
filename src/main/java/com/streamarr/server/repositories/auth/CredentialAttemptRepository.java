package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import java.time.Instant;

public interface CredentialAttemptRepository {

  CredentialAttemptAdmission reserve(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy);

  void complete(CredentialAttemptReservation reservation, CredentialAttemptResult result);

  int deleteAttemptedBefore(Instant cutoff);
}
