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

public class FakeCredentialAttemptRepository implements CredentialAttemptRepository {

  private final Map<UUID, RecordedAttempt> attempts = new LinkedHashMap<>();
  private Duration rejection;

  @Override
  public CredentialAttemptAdmission reserve(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy, Instant attemptedAt) {
    if (rejection != null) {
      return new CredentialAttemptAdmission.Blocked(rejection);
    }

    var reservation = new CredentialAttemptReservation(UUID.randomUUID(), target);
    attempts.put(reservation.id(), new RecordedAttempt(reservation, attemptedAt));
    return new CredentialAttemptAdmission.Reserved(reservation);
  }

  @Override
  public void complete(
      CredentialAttemptReservation reservation,
      CredentialAttemptResult result,
      Instant completedAt) {
    attempts.get(reservation.id()).complete(result, completedAt);
  }

  @Override
  public int deleteAttemptedBefore(Instant cutoff) {
    var expired =
        attempts.values().stream()
            .filter(attempt -> attempt.attemptedAt().isBefore(cutoff))
            .map(attempt -> attempt.reservation().id())
            .toList();
    expired.forEach(attempts::remove);
    return expired.size();
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

  public List<AttemptSnapshot> attempts() {
    return attempts.values().stream().map(RecordedAttempt::snapshot).toList();
  }

  public List<CredentialAttemptTarget> targets() {
    return attempts().stream().map(AttemptSnapshot::target).toList();
  }

  private static final class RecordedAttempt {

    private final CredentialAttemptReservation reservation;
    private final Instant attemptedAt;
    private CredentialAttemptResult result;
    private Instant completedAt;

    private RecordedAttempt(CredentialAttemptReservation reservation, Instant attemptedAt) {
      this.reservation = reservation;
      this.attemptedAt = attemptedAt;
    }

    private void complete(CredentialAttemptResult completedResult, Instant completionTime) {
      result = completedResult;
      completedAt = completionTime;
    }

    private CredentialAttemptReservation reservation() {
      return reservation;
    }

    private Instant attemptedAt() {
      return attemptedAt;
    }

    private AttemptSnapshot snapshot() {
      return new AttemptSnapshot(
          reservation.id(), reservation.target(), attemptedAt, completedAt, result);
    }
  }

  public record AttemptSnapshot(
      UUID id,
      CredentialAttemptTarget target,
      Instant attemptedAt,
      Instant completedAt,
      CredentialAttemptResult result) {}
}
