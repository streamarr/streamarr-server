package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
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
  public @NonNull Optional<ProfileManagerInvitation> findByPublicId(String publicId) {
    return database.values().stream()
        .filter(invitation -> publicId.equals(invitation.getPublicId()))
        .findFirst();
  }

  @Override
  public List<ProfileManagerInvitation> findByRecipientAccountIdAndStatus(
      UUID recipientAccountId, ProfileManagerInvitationStatus status) {
    return database.values().stream()
        .filter(invitation -> recipientAccountId.equals(invitation.getRecipientAccountId()))
        .filter(invitation -> invitation.getStatus() == status)
        .toList();
  }

  @Override
  public List<ProfileManagerInvitation> findByProfileIdAndStatus(
      UUID profileId, ProfileManagerInvitationStatus status) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .filter(invitation -> invitation.getStatus() == status)
        .toList();
  }

  @Override
  public boolean tryDecide(UUID invitationId, ProfileManagerInvitationStatus target, Instant now) {
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
  public int invalidatePendingForProfileAndRecipient(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
        invitation ->
            profileId.equals(invitation.getProfileId())
                && recipientAccountId.equals(invitation.getRecipientAccountId()),
        reason,
        now);
  }

  @Override
  public int invalidatePendingForProfile(UUID profileId, String reason, Instant now) {
    return invalidate(invitation -> profileId.equals(invitation.getProfileId()), reason, now);
  }

  @Override
  public int invalidatePendingInvitedBy(
      UUID inviterAccountId, UUID profileId, String reason, Instant now) {
    return invalidate(
        invitation ->
            inviterAccountId.equals(invitation.getInviterAccountId())
                && profileId.equals(invitation.getProfileId()),
        reason,
        now);
  }

  @Override
  public int invalidatePendingForRecipient(UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
        invitation -> recipientAccountId.equals(invitation.getRecipientAccountId()), reason, now);
  }

  private int invalidate(Predicate<ProfileManagerInvitation> scope, String reason, Instant now) {
    var pending =
        database.values().stream()
            .filter(invitation -> invitation.getStatus() == ProfileManagerInvitationStatus.PENDING)
            .filter(scope)
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
