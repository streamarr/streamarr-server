package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;

public class FakeProfileManagerInvitationRepository
    extends FakeJpaRepository<ProfileManagerInvitation>
    implements ProfileManagerInvitationRepository {

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForProfile(
      UUID profileId, Instant now, Seek seek) {
    return rowsPending(invitation -> profileId.equals(invitation.getProfileId()), now, seek);
  }

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForRecipient(
      UUID recipientAccountId, Instant now, Seek seek) {
    return rowsPending(
        invitation -> recipientAccountId.equals(invitation.getRecipientAccountId()), now, seek);
  }

  private List<Row<ProfileManagerInvitation>> rowsPending(
      Predicate<ProfileManagerInvitation> scope, Instant now, Seek seek) {
    return InMemoryKeyset.byCreationTime(
        database.values().stream()
            .filter(scope)
            .filter(
                invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.PENDING)
            .toList(),
        seek);
  }

  @Override
  public @NonNull Optional<ProfileManagerInvitation> findByPublicId(String publicId) {
    return database.values().stream()
        .filter(invitation -> publicId.equals(invitation.getPublicId()))
        .findFirst();
  }

  private boolean tryTransitionPending(
      UUID invitationId, ProfileManagerInvitationStatus target, Instant now) {
    var invitation = database.get(invitationId);
    if (invitation == null
        || invitation.getStatus() != ProfileManagerInvitationStatus.PENDING
        || !invitation.getExpiresAt().isAfter(now)) {
      return false;
    }

    invitation.setStatus(target);
    invitation.setDecidedAt(now);
    return true;
  }

  @Override
  public boolean tryCancelPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.CANCELED, now);
  }

  @Override
  public boolean tryAcceptPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.ACCEPTED, now);
  }

  @Override
  public boolean tryDeclinePending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.DECLINED, now);
  }

  @Override
  public boolean tryInvalidatePending(UUID invitationId, String reason, Instant now) {
    return invalidate(invitation -> invitationId.equals(invitation.getId()), reason, now) > 0;
  }

  @Override
  public int invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
        invitation ->
            profileId.equals(invitation.getProfileId())
                && recipientAccountId.equals(invitation.getRecipientAccountId()),
        reason,
        now);
  }

  @Override
  public int invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    return invalidate(invitation -> profileId.equals(invitation.getProfileId()), reason, now);
  }

  @Override
  public int invalidatePendingInvitationsByInviterAccountIdAndProfileId(
      UUID inviterAccountId, UUID profileId, String reason, Instant now) {
    return invalidate(
        invitation ->
            inviterAccountId.equals(invitation.getInviterAccountId())
                && profileId.equals(invitation.getProfileId()),
        reason,
        now);
  }

  @Override
  public int invalidatePendingInvitedBy(UUID inviterAccountId, String reason, Instant now) {
    return invalidate(
        invitation -> inviterAccountId.equals(invitation.getInviterAccountId()), reason, now);
  }

  @Override
  public int invalidatePendingByRecipientAccountId(
      UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
        invitation -> recipientAccountId.equals(invitation.getRecipientAccountId()), reason, now);
  }

  private int invalidate(Predicate<ProfileManagerInvitation> scope, String reason, Instant now) {
    database.values().stream()
        .filter(invitation -> invitation.getStatus() == ProfileManagerInvitationStatus.PENDING)
        .filter(scope)
        .filter(invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.EXPIRED)
        .forEach(
            invitation -> {
              invitation.setStatus(ProfileManagerInvitationStatus.EXPIRED);
              invitation.setDecidedAt(now);
            });
    var pending =
        database.values().stream()
            .filter(scope)
            .filter(
                invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.PENDING)
            .toList();
    pending.forEach(
        invitation -> {
          invitation.setStatus(ProfileManagerInvitationStatus.INVALIDATED);
          invitation.setInvalidationReason(reason);
          invitation.setDecidedAt(now);
        });
    return pending.size();
  }
}
