package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.exceptions.CredentialVerificationException;
import com.streamarr.server.exceptions.TooManyAttemptsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import java.time.Duration;
import java.util.function.Function;
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

  public CredentialAttemptReservation reserve(CredentialAttemptMetadata metadata) {
    try {
      return switch (repository.reserve(metadata, policies.policyFor(metadata.kind()))) {
        case CredentialAttemptAdmission.Reserved(var reservation) -> reservation;
        case CredentialAttemptAdmission.Blocked(var retryAfter) ->
            throw blocked(metadata, retryAfter);
      };
    } catch (DataAccessException | TransactionException exception) {
      throw unavailable("reserving", metadata, exception);
    }
  }

  /**
   * Reserves an attempt, runs the verifier after the reservation transaction ends, and journals the
   * outcome: SUCCEEDED when it returns, FAILED when it throws a {@link
   * CredentialVerificationException}, and left pending — abandoned after five minutes (ADR 0028) —
   * when it fails for any other reason.
   */
  public <T> T attempt(CredentialAttemptMetadata metadata, Supplier<T> verification) {
    var reservation = reserve(metadata);
    var verified = verify(reservation, verification);
    complete(reservation, CredentialAttemptResult.SUCCEEDED);
    return verified;
  }

  /**
   * Verifies before opening a transaction, then commits the database mutation and successful
   * journal outcome together. The mutation may recheck database state but must not hash credentials
   * or perform external I/O. Call outside a transaction.
   */
  public <V, T> T attempt(
      CredentialAttemptMetadata metadata, Supplier<V> verification, Function<V, T> mutation) {
    var reservation = reserve(metadata);
    return verify(
        reservation,
        () -> {
          var verified = verification.get();
          return completeWith(reservation, () -> mutation.apply(verified));
        });
  }

  private <T> T verify(CredentialAttemptReservation reservation, Supplier<T> verification) {
    try {
      return verification.get();
    } catch (CredentialVerificationException refused) {
      recordRefusal(reservation, refused);
      throw refused;
    } catch (RuntimeException failure) {
      log.warn(
          "Credential attempt left pending after an unexpected failure: {}",
          describe(reservation.metadata()),
          failure);
      throw failure;
    }
  }

  private void recordRefusal(
      CredentialAttemptReservation reservation, CredentialVerificationException refused) {
    try {
      complete(reservation, CredentialAttemptResult.FAILED);
    } catch (CredentialAttemptUnavailableException unavailable) {
      unavailable.addSuppressed(refused);
      throw unavailable;
    }
  }

  public void attempt(CredentialAttemptMetadata metadata, Verification verification) {
    attempt(
        metadata,
        () -> {
          verification.verify();
          return null;
        });
  }

  public void complete(CredentialAttemptReservation reservation, CredentialAttemptResult result) {
    try {
      repository.complete(reservation, result);
    } catch (DataAccessException | TransactionException exception) {
      throw unavailable("completing", reservation.metadata(), exception);
    }
  }

  private <T> T completeWith(CredentialAttemptReservation reservation, Supplier<T> mutation) {
    try {
      return repository.completeWith(reservation, mutation);
    } catch (DataAccessException | TransactionException exception) {
      throw unavailable("completing", reservation.metadata(), exception);
    }
  }

  private static TooManyAttemptsException blocked(
      CredentialAttemptMetadata metadata, Duration retryAfter) {
    log.warn("Credential attempt blocked: {} retryAfter={}", describe(metadata), retryAfter);
    return switch (metadata.kind()) {
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
      String operation, CredentialAttemptMetadata metadata, RuntimeException cause) {
    log.error(
        "Credential journal unavailable while {} an attempt: {}",
        operation,
        describe(metadata),
        cause);
    return new CredentialAttemptUnavailableException(cause);
  }

  /** Identifiers only: the client address is observational and never belongs in a log line. */
  private static String describe(CredentialAttemptMetadata metadata) {
    return "kind=%s accountId=%s profileId=%s credentialId=%s"
        .formatted(
            metadata.kind(), metadata.accountId(), metadata.profileId(), metadata.credentialId());
  }

  /** A credential check with no result of its own; it refuses by throwing. */
  @FunctionalInterface
  public interface Verification {
    void verify();
  }
}
