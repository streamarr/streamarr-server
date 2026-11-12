package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileManagerInvitationsFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;

public class FakeProfileManagerInvitationRepository
    extends FakeJpaRepository<ProfileManagerInvitation>
    implements ProfileManagerInvitationRepository {

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForProfile(
      ProfileManagerInvitationsFilter filter, Instant now, Seek seek) {
    return rowsPending(
        invitation ->
            filter.profileId().equals(invitation.getProfileId())
                && (filter.asItsManagersReadThem() || answerable(invitation)),
        now,
        seek);
  }

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForRecipient(
      UUID recipientAccountId, Instant now, Seek seek) {
    return rowsPending(
        invitation ->
            recipientAccountId.equals(invitation.getRecipientAccountId()) && answerable(invitation),
        now,
        seek);
  }

  private static boolean answerable(ProfileManagerInvitation invitation) {
    return invitation.getRecipientInvalidatedAt() == null;
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

  @Override
  public boolean existsWaitingForProfile(UUID profileId, Instant now) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .anyMatch(invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.PENDING);
  }

  @Override
  public int countLivePendingForProfileExceptTo(UUID profileId, String email, Instant now) {
    return (int)
        database.values().stream()
            .filter(invitation -> profileId.equals(invitation.getProfileId()))
            .filter(
                invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.PENDING)
            .filter(invitation -> !email.equalsIgnoreCase(invitation.getRecipientEmail()))
            .count();
  }

  private Optional<ChangedManagerInvitation> tryTransitionPending(
      UUID invitationId, ProfileManagerInvitationStatus target, Instant now) {
    var invitation = database.get(invitationId);
    if (invitation == null
        || invitation.getStatus() != ProfileManagerInvitationStatus.PENDING
        || !invitation.getExpiresAt().isAfter(now)
        || (target != ProfileManagerInvitationStatus.CANCELED && !answerable(invitation))) {
      return Optional.empty();
    }

    invitation.setStatus(target);
    invitation.setDecidedAt(now);
    return Optional.of(ChangedManagerInvitation.of(invitation));
  }

  @Override
  public Optional<ChangedManagerInvitation> tryCancelPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.CANCELED, now);
  }

  @Override
  public Optional<ChangedManagerInvitation> tryAcceptPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.ACCEPTED, now);
  }

  @Override
  public Optional<ChangedManagerInvitation> tryDeclinePending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.DECLINED, now);
  }

  @Override
  public List<ChangedManagerInvitation> tryInvalidatePending(
      UUID invitationId, String reason, Instant now) {
    return invalidate(invitation -> invitationId.equals(invitation.getId()), reason, now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByProfileIdAndRecipientEmail(
      UUID profileId, String email, String reason, Instant now) {
    return invalidate(
        invitation ->
            profileId.equals(invitation.getProfileId())
                && email.equalsIgnoreCase(invitation.getRecipientEmail()),
        reason,
        now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    return invalidateForItsAccount(
        invitation ->
            profileId.equals(invitation.getProfileId())
                && recipientAccountId.equals(invitation.getRecipientAccountId()),
        reason,
        now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByProfileId(
      UUID profileId, String reason, Instant now) {
    return invalidate(invitation -> profileId.equals(invitation.getProfileId()), reason, now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingInvitationsByInviterAccountIdAndProfileId(
      UUID inviterAccountId, UUID profileId, String reason, Instant now) {
    return invalidate(
        invitation ->
            inviterAccountId.equals(invitation.getInviterAccountId())
                && profileId.equals(invitation.getProfileId()),
        reason,
        now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingInvitedBy(
      UUID inviterAccountId, String reason, Instant now) {
    return invalidate(
        invitation -> inviterAccountId.equals(invitation.getInviterAccountId()), reason, now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByRecipientAccountId(
      UUID recipientAccountId, String reason, Instant now) {
    return invalidateForItsAccount(
        invitation -> recipientAccountId.equals(invitation.getRecipientAccountId()), reason, now);
  }

  @Override
  public List<UUID> findIdsByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .map(ProfileManagerInvitation::getId)
        .toList();
  }

  @Override
  public List<ChangedManagerInvitation> materializeExpiredPending(Instant now) {
    return materializeExpiredPending(_ -> true, now);
  }

  private List<ChangedManagerInvitation> invalidate(
      Predicate<ProfileManagerInvitation> scope, String reason, Instant now) {
    var expired = materializeExpiredPending(scope, now);
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
    return Stream.concat(expired.stream(), pending.stream().map(ChangedManagerInvitation::of))
        .toList();
  }

  private List<ChangedManagerInvitation> invalidateForItsAccount(
      Predicate<ProfileManagerInvitation> scope, String reason, Instant now) {
    var expired = materializeExpiredPending(scope, now);
    var answerable =
        database.values().stream()
            .filter(scope)
            .filter(
                invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.PENDING)
            .filter(FakeProfileManagerInvitationRepository::answerable)
            .toList();
    answerable.forEach(
        invitation -> {
          invitation.setRecipientInvalidatedAt(now);
          invitation.setRecipientInvalidationReason(reason);
        });
    return Stream.concat(expired.stream(), answerable.stream().map(ChangedManagerInvitation::of))
        .toList();
  }

  private List<ChangedManagerInvitation> materializeExpiredPending(
      Predicate<ProfileManagerInvitation> scope, Instant now) {
    var lapsed =
        database.values().stream()
            .filter(invitation -> invitation.getStatus() == ProfileManagerInvitationStatus.PENDING)
            .filter(scope)
            .filter(
                invitation -> invitation.statusAt(now) == ProfileManagerInvitationStatus.EXPIRED)
            .toList();
    lapsed.forEach(
        invitation -> {
          invitation.setStatus(ProfileManagerInvitationStatus.EXPIRED);
          invitation.setDecidedAt(now);
        });
    return lapsed.stream().map(ChangedManagerInvitation::of).toList();
  }
}
