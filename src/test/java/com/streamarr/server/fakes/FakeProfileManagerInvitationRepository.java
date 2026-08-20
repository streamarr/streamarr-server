package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;

public class FakeProfileManagerInvitationRepository
    extends FakeJpaRepository<ProfileManagerInvitation>
    implements ProfileManagerInvitationRepository {

  @Override
  public List<ProfileManagerInvitation> findPendingByProfileId(
      UUID profileId, Instant now, KeysetPaginationOptions options) {
    return findPage(invitation -> profileId.equals(invitation.getProfileId()), now, options);
  }

  @Override
  public List<ProfileManagerInvitation> findPendingByRecipientAccountId(
      UUID recipientAccountId, Instant now, KeysetPaginationOptions options) {
    return findPage(
        invitation -> recipientAccountId.equals(invitation.getRecipientAccountId()), now, options);
  }

  private List<ProfileManagerInvitation> findPage(
      Predicate<ProfileManagerInvitation> scope, Instant now, KeysetPaginationOptions options) {
    var ordered =
        database.values().stream()
            .filter(scope)
            .filter(
                invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.PENDING)
            .sorted(
                Comparator.comparing(ProfileManagerInvitation::getCreatedOn)
                    .reversed()
                    .thenComparing(invitation -> invitation.getId().toString()))
            .toList();
    var cursorIndex =
        options
            .getCursorId()
            .map(
                cursorId ->
                    ordered.stream()
                        .map(ProfileManagerInvitation::getId)
                        .toList()
                        .indexOf(cursorId))
            .orElse(-1);
    if (options.getCursorId().isPresent() && cursorIndex < 0) {
      throw new InvalidPaginationCursorException("Cursor no longer identifies an item.");
    }

    var pagination = options.getPaginationOptions();
    if (pagination.getPaginationDirection() == PaginationDirection.REVERSE) {
      var to = options.getCursorId().isPresent() ? cursorIndex + 1 : ordered.size();
      var from = Math.max(0, to - pagination.getLimit() - 2);
      return ordered.subList(from, to);
    }

    var from = options.getCursorId().isPresent() ? cursorIndex : 0;
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var to = Math.min(ordered.size(), from + pagination.getLimit() + extraRows);
    return ordered.subList(from, to);
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
  public int invalidatePendingForInviter(UUID inviterAccountId, String reason, Instant now) {
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
