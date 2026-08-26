package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.exceptions.CredentialAttemptRejectedException;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
@RequiredArgsConstructor
public class CredentialAttemptGate {

  private final CredentialAttemptRepository repository;
  private final CredentialAttemptPolicyProvider policies;
  private final Clock clock;

  public CredentialAttemptReservation reserve(CredentialAttemptTarget target) {
    try {
      return switch (repository.reserve(
          target, policies.policyFor(target.kind()), clock.instant())) {
        case CredentialAttemptAdmission.Reserved(var reservation) -> reservation;
        case CredentialAttemptAdmission.Blocked(var retryAfter) ->
            throw new CredentialAttemptRejectedException(retryAfter);
      };
    } catch (DataAccessException | TransactionException exception) {
      throw new CredentialAttemptUnavailableException(exception);
    }
  }

  public void complete(CredentialAttemptReservation reservation, CredentialAttemptResult result) {
    try {
      repository.complete(reservation, result, clock.instant());
    } catch (DataAccessException | TransactionException exception) {
      throw new CredentialAttemptUnavailableException(exception);
    }
  }
}
