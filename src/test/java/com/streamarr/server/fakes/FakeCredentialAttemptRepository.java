package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptHistory;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.exceptions.CredentialAttemptNotPendingException;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import com.streamarr.server.services.auth.CredentialAttemptGate;
import com.streamarr.server.services.auth.StandardCredentialAttemptPolicyProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory credential attempt journal. Admission uses the production arithmetic ({@link
 * CredentialAttemptPolicy.Limited#retryAfter}) over the recorded attempts, so service tests prove
 * real limits and resets; {@link #rejectReservations} forces a block regardless of history.
 */
public class FakeCredentialAttemptRepository implements CredentialAttemptRepository {

  /** Mirrors the jOOQ repository: a pending reservation is abandoned after five minutes. */
  private static final Duration ABANDONED_RESERVATION_TIMEOUT = Duration.ofMinutes(5);

  private final Map<UUID, AttemptSnapshot> attempts = new LinkedHashMap<>();
  private Duration rejection;
  private RuntimeException failure;

  @Override
  public CredentialAttemptAdmission reserve(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy, Instant attemptedAt) {
    failIfArmed();
    return blockedBy(policy, target, attemptedAt).orElseGet(() -> journal(target, attemptedAt));
  }

  @Override
  public void complete(
      CredentialAttemptReservation reservation,
      CredentialAttemptResult result,
      Instant completedAt) {
    failIfArmed();
    var pending = attempts.get(reservation.id());
    if (pending == null || pending.completedAt() != null) {
      throw new CredentialAttemptNotPendingException();
    }

    var completion =
        completedAt.isBefore(pending.attemptedAt()) ? pending.attemptedAt() : completedAt;
    attempts.put(
        reservation.id(),
        new AttemptSnapshot(
            pending.id(), pending.target(), pending.attemptedAt(), completion, result));
  }

  @Override
  public int deleteAttemptedBefore(Instant cutoff) {
    failIfArmed();
    var before = attempts.size();
    attempts.values().removeIf(attempt -> attempt.attemptedAt().isBefore(cutoff));
    return before - attempts.size();
  }

  public CredentialAttemptGate gate(Clock clock) {
    return new CredentialAttemptGate(this, new StandardCredentialAttemptPolicyProvider(), clock);
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

  private Optional<CredentialAttemptAdmission> blockedBy(
      CredentialAttemptPolicy policy, CredentialAttemptTarget target, Instant now) {
    if (rejection != null) {
      return Optional.of(new CredentialAttemptAdmission.Blocked(rejection));
    }

    if (!(policy instanceof CredentialAttemptPolicy.Limited limited) || !target.isResolved()) {
      return Optional.empty();
    }

    return limited
        .retryAfter(history(target, limited, now), now)
        .map(CredentialAttemptAdmission.Blocked::new);
  }

  private CredentialAttemptAdmission journal(CredentialAttemptTarget target, Instant attemptedAt) {
    var reservation = new CredentialAttemptReservation(UUID.randomUUID(), target);
    attempts.put(
        reservation.id(), new AttemptSnapshot(reservation.id(), target, attemptedAt, null, null));
    return new CredentialAttemptAdmission.Reserved(reservation);
  }

  /** The same selection the jOOQ repository makes, over the in-memory rows. */
  private CredentialAttemptHistory history(
      CredentialAttemptTarget target, CredentialAttemptPolicy.Limited policy, Instant now) {
    var journal =
        attempts.values().stream().filter(attempt -> sameTarget(attempt.target(), target)).toList();
    var latestSuccess =
        journal.stream()
            .filter(attempt -> attempt.result() == CredentialAttemptResult.SUCCEEDED)
            .map(AttemptSnapshot::completedAt)
            .max(Comparator.naturalOrder());
    var earliestRelevant = now.minus(policy.failureWindow()).minus(policy.throttleDuration());
    var failures =
        journal.stream()
            .filter(attempt -> attempt.result() == CredentialAttemptResult.FAILED)
            .map(AttemptSnapshot::completedAt)
            .filter(completedAt -> latestSuccess.map(completedAt::isAfter).orElse(true))
            .filter(completedAt -> !completedAt.isBefore(earliestRelevant))
            .sorted()
            .toList();
    var pendingExpiries =
        journal.stream()
            .filter(attempt -> attempt.result() == null)
            .map(AttemptSnapshot::attemptedAt)
            .filter(attemptedAt -> attemptedAt.isAfter(now.minus(ABANDONED_RESERVATION_TIMEOUT)))
            .map(attemptedAt -> attemptedAt.plus(ABANDONED_RESERVATION_TIMEOUT))
            .toList();
    return new CredentialAttemptHistory(failures, pendingExpiries);
  }

  /** The client address is observational and never part of the throttle target. */
  private static boolean sameTarget(CredentialAttemptTarget left, CredentialAttemptTarget right) {
    return left.kind() == right.kind()
        && Objects.equals(left.accountId(), right.accountId())
        && Objects.equals(left.profileId(), right.profileId())
        && Objects.equals(left.credentialId(), right.credentialId());
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
