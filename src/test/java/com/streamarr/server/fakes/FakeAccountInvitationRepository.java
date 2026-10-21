package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public class FakeAccountInvitationRepository extends FakeJpaRepository<AccountInvitation>
    implements AccountInvitationRepository {

  @Override
  public List<Row<AccountInvitation>> rowsBeyond(Seek seek) {
    return InMemoryKeyset.rowsBeyond(
        database.values().stream()
            .map(
                invitation ->
                    new Row<>(
                        invitation,
                        new Position(
                            new SortValue.OfInstant(invitation.getCreatedOn()),
                            invitation.getId())))
            .toList(),
        seek);
  }

  @Override
  public void lockInvitationIssuanceForRecipientEmail(String recipientEmail) {
    // Unit tests are single-threaded; CredentialIssuanceReplacementRaceIT and
    // CredentialIssuanceLockTimeoutIT prove the cross-instance lock against PostgreSQL.
  }

  @Override
  public Optional<AccountInvitation> findByPublicId(String publicId) {
    return database.values().stream()
        .filter(invitation -> publicId.equals(invitation.getPublicId()))
        .findFirst();
  }

  @Override
  public boolean markAcceptedIfPendingAndUnexpired(UUID invitationId, Instant now) {
    return markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.ACCEPTED, now);
  }

  @Override
  public boolean markDeclinedIfPendingAndUnexpired(UUID invitationId, Instant now) {
    return markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.DECLINED, now);
  }

  @Override
  public Optional<AccountInvitation> cancelIfPendingAndUnexpired(UUID invitationId, Instant now) {
    if (!markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.CANCELED, now)) {
      return Optional.empty();
    }

    return findById(invitationId);
  }

  private boolean markIfPendingAndUnexpired(
      UUID invitationId, AccountInvitationStatus target, Instant now) {
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
  public int invalidatePendingInvitationsForRecipientEmail(
      String recipientEmail, String reason, Instant now) {
    return invalidate(
        invitation ->
            invitation.getRecipientEmail().equalsIgnoreCase(recipientEmail)
                && invitation.getExpiresAt().isAfter(now),
        reason,
        now);
  }

  @Override
  public int invalidatePendingByHouseholdId(UUID householdId, String reason, Instant now) {
    return invalidate(invitation -> householdId.equals(invitation.getHouseholdId()), reason, now);
  }

  @Override
  public int invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    return invalidate(
        invitation ->
            profileId.equals(invitation.getProfileId()) && invitation.getExpiresAt().isAfter(now),
        reason,
        now);
  }

  @Override
  public int invalidatePendingInvitationsIssuedBy(
      UUID issuerAccountId, String reason, Instant now) {
    return invalidate(
        invitation ->
            issuerAccountId.equals(invitation.getIssuerAccountId())
                && invitation.getExpiresAt().isAfter(now),
        reason,
        now);
  }

  @Override
  public int expirePendingInvitationsForRecipientEmail(String recipientEmail, Instant now) {
    var expired =
        database.values().stream()
            .filter(invitation -> invitation.getStatus() == AccountInvitationStatus.PENDING)
            .filter(invitation -> invitation.getRecipientEmail().equalsIgnoreCase(recipientEmail))
            .filter(invitation -> !invitation.getExpiresAt().isAfter(now))
            .toList();
    expired.forEach(
        invitation -> {
          invitation.setStatus(AccountInvitationStatus.EXPIRED);
          invitation.setDecidedAt(now);
        });
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
