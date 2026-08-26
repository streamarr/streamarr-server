package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import com.streamarr.server.services.auth.CredentialAttemptGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records reservations and completions; admission is switched by the test ({@link
 * #rejectReservations}) rather than computed, so service tests prove what they journal, not the
 * window arithmetic that {@code JooqCredentialAttemptRepositoryIT} owns.
 */
public class FakeCredentialAttemptRepository implements CredentialAttemptRepository {

  private final Map<UUID, AttemptSnapshot> attempts = new LinkedHashMap<>();
  private Duration rejection;
  private RuntimeException failure;

  @Override
  public CredentialAttemptAdmission reserve(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy, Instant attemptedAt) {
    failIfArmed();
    if (rejection != null) {
      return new CredentialAttemptAdmission.Blocked(rejection);
    }

    var reservation = new CredentialAttemptReservation(UUID.randomUUID(), target);
    attempts.put(
        reservation.id(), new AttemptSnapshot(reservation.id(), target, attemptedAt, null, null));
    return new CredentialAttemptAdmission.Reserved(reservation);
  }

  @Override
  public void complete(
      CredentialAttemptReservation reservation,
      CredentialAttemptResult result,
      Instant completedAt) {
    failIfArmed();
    var pending = attempts.get(reservation.id());
    if (pending == null || pending.completedAt() != null) {
      throw new IllegalStateException("Credential attempt reservation is not pending");
    }

    attempts.put(
        reservation.id(),
        new AttemptSnapshot(
            pending.id(), pending.target(), pending.attemptedAt(), completedAt, result));
  }

  @Override
  public int deleteAttemptedBefore(Instant cutoff) {
    failIfArmed();
    var before = attempts.size();
    attempts.values().removeIf(attempt -> attempt.attemptedAt().isBefore(cutoff));
    return before - attempts.size();
  }

  public CredentialAttemptGate gate(Clock clock) {
    return new CredentialAttemptGate(this, _ -> new CredentialAttemptPolicy.Unlimited(), clock);
  }

  public void rejectReservations(Duration retryAfter) {
    rejection = retryAfter;
  }

  public void allowReservations() {
    rejection = null;
  }

  /** Every later call throws {@code failure}, standing in for a lost database or lock. */
  public void failWith(RuntimeException failure) {
    this.failure = failure;
  }

  public List<AttemptSnapshot> attempts() {
    return List.copyOf(attempts.values());
  }

  private void failIfArmed() {
    if (failure != null) {
      throw failure;
    }
  }

  public record AttemptSnapshot(
      UUID id,
      CredentialAttemptTarget target,
      Instant attemptedAt,
      Instant completedAt,
      CredentialAttemptResult result) {}
}
