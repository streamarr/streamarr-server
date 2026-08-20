package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public class FakeAccountInvitationRepository extends FakeJpaRepository<AccountInvitation>
    implements AccountInvitationRepository {

  @Override
  public void lockRecipientForReplacement(String recipientEmail) {
    // Unit tests are single-threaded; PostgreSQL integration tests prove cross-instance locking.
  }

  @Override
  public Optional<AccountInvitation> findByPublicId(String publicId) {
    return database.values().stream()
        .filter(invitation -> publicId.equals(invitation.getPublicId()))
        .findFirst();
  }

  @Override
  public boolean tryDecide(UUID invitationId, AccountInvitationStatus target, Instant now) {
    var decided =
        findById(invitationId)
            .filter(invitation -> invitation.getStatus() == AccountInvitationStatus.PENDING)
            .filter(invitation -> invitation.getExpiresAt().isAfter(now));
    decided.ifPresent(
        invitation -> {
          invitation.setStatus(target);
          invitation.setDecidedAt(now);
        });
    return decided.isPresent();
  }

  @Override
  public int invalidatePendingForEmail(String recipientEmail, String reason, Instant now) {
    return invalidate(
        invitation -> invitation.getRecipientEmail().equalsIgnoreCase(recipientEmail), reason, now);
  }

  @Override
  public int invalidatePendingForProfile(UUID profileId, String reason, Instant now) {
    return invalidate(invitation -> profileId.equals(invitation.getProfileId()), reason, now);
  }

  @Override
  public int invalidateIssuedBy(UUID issuerAccountId, String reason, Instant now) {
    return invalidate(
        invitation -> issuerAccountId.equals(invitation.getIssuerAccountId()), reason, now);
  }

  @Override
  public int sweepExpired(Instant now) {
    var expired =
        database.values().stream()
            .filter(invitation -> invitation.getStatus() == AccountInvitationStatus.PENDING)
            .filter(invitation -> !invitation.getExpiresAt().isAfter(now))
            .toList();
    expired.forEach(invitation -> invitation.setStatus(AccountInvitationStatus.EXPIRED));
    return expired.size();
  }

  private int invalidate(Predicate<AccountInvitation> scope, String reason, Instant now) {
    var affected =
        database.values().stream()
            .filter(invitation -> invitation.getStatus() == AccountInvitationStatus.PENDING)
            .filter(scope)
            .toList();
    affected.forEach(
        invitation -> {
          invitation.setStatus(AccountInvitationStatus.INVALIDATED);
          invitation.setInvalidationReason(reason);
          invitation.setDecidedAt(now);
        });
    return affected.size();
  }
}
