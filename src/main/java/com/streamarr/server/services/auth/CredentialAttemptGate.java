package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.exceptions.CredentialVerificationException;
import com.streamarr.server.exceptions.TooManyAttemptsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Slf4j
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
            throw blocked(target, retryAfter);
      };
    } catch (DataAccessException | TransactionException exception) {
      throw unavailable("reserving", target, exception);
    }
  }

  /**
   * Reserves an attempt, runs the verifier outside any transaction or lock, and journals the
   * outcome: SUCCEEDED when it returns, FAILED when it throws a {@link
   * CredentialVerificationException}, and left pending — abandoned after five minutes (ADR 0028) —
   * when it fails for any other reason.
   */
  public <T> T attempt(CredentialAttemptTarget target, Supplier<T> verification) {
    var reservation = reserve(target);
    T verified;
    try {
      verified = verification.get();
    } catch (CredentialVerificationException refused) {
      complete(reservation, CredentialAttemptResult.FAILED);
      throw refused;
    } catch (RuntimeException failure) {
      log.warn(
          "Credential attempt left pending after an unexpected failure: {}",
          describe(target),
          failure);
      throw failure;
    }

    complete(reservation, CredentialAttemptResult.SUCCEEDED);
    return verified;
  }

  public void attempt(CredentialAttemptTarget target, Verification verification) {
    attempt(
        target,
        () -> {
          verification.verify();
          return null;
        });
  }

  public void complete(CredentialAttemptReservation reservation, CredentialAttemptResult result) {
    try {
      repository.complete(reservation, result, clock.instant());
    } catch (DataAccessException | TransactionException exception) {
      throw unavailable("completing", reservation.target(), exception);
    }
  }

  private static TooManyAttemptsException blocked(
      CredentialAttemptTarget target, Duration retryAfter) {
    log.warn("Credential attempt blocked: {} retryAfter={}", describe(target), retryAfter);
    return switch (target.kind()) {
      case ACCOUNT_LOGIN -> new TooManyLoginAttemptsException(retryAfter);
      case DEVICE_PAIRING_CODE -> new TooManyDeviceAttemptsException(retryAfter);
      case ACCOUNT_PASSWORD_VERIFICATION,
          PROFILE_PIN,
          ACCOUNT_INVITATION_CODE,
          PASSWORD_RESET_CODE,
          PROFILE_MANAGER_INVITATION_CODE ->
          new TooManyCredentialAttemptsException(retryAfter);
    };
  }

  private static CredentialAttemptUnavailableException unavailable(
      String operation, CredentialAttemptTarget target, RuntimeException cause) {
    log.error(
        "Credential journal unavailable while {} an attempt: {}",
        operation,
        describe(target),
        cause);
    return new CredentialAttemptUnavailableException(cause);
  }

  /** Identifiers only: the client address is observational and never belongs in a log line. */
  private static String describe(CredentialAttemptTarget target) {
    return "kind=%s accountId=%s profileId=%s credentialId=%s"
        .formatted(target.kind(), target.accountId(), target.profileId(), target.credentialId());
  }

  /** A credential check with no result of its own; it refuses by throwing. */
  @FunctionalInterface
  public interface Verification {
    void verify();
  }
}
