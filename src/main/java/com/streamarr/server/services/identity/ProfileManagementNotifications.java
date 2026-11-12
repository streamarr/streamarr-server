package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ManagerInvitationAction;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.RequestAnswer;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.events.auth.ProfileManagerInvitationChangedEvent;
import com.streamarr.server.services.events.notification.ProfileManagementNotificationKinds;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationAnswer;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationReceivedSnapshot;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the Account that an invitation to manage a Profile names that it may accept it, settles
 * that request when the invitation stops waiting for it, and tells the inviter how the invited
 * Account answered (ADR 0063 §The Profile management kinds). It hears each invitation write inside
 * the write's transaction, so what it tells commits or rolls back with the write. An invitation
 * names an Account only when that Account may accept it, which the invite reads and holds until it
 * commits, so never one of the Profile's managers, and only a manager invites, so the Account that
 * acted is never the one told.
 */
@Component
@RequiredArgsConstructor
class ProfileManagementNotifications {

  private final ProfileRepository profileRepository;
  private final ApplicationEventPublisher events;

  @EventListener
  public void onProfileManagerInvitationChanged(ProfileManagerInvitationChangedEvent change) {
    switch (change.action()) {
      case ManagerInvitationAction.Issued() -> tellReceived(change);
      case ManagerInvitationAction.Answered _ -> answer(change);
      case ManagerInvitationAction.Canceled _,
          ManagerInvitationAction.StoppedWaiting _,
          ManagerInvitationAction.InvalidatedForItsAccount _ ->
          resolveRequest(change);
    }
  }

  // Every invitation is told, with the Profile's name read under the invite's lock on the Profile,
  // whether or not it names an Account, so an invitation that nobody may accept runs the same
  // statements as any other and says nothing of who has an Account (ADR 0024).
  private void tellReceived(ProfileManagerInvitationChangedEvent change) {
    var invitation = change.invitation();
    events.publishEvent(
        TellNotificationEvent.builder()
            .kind(ProfileManagementNotificationKinds.PROFILE_MANAGER_INVITATION_RECEIVED)
            .subject(SubjectReference.profileManagerInvitation(invitation.invitationId()))
            .recipients(
                invitation.recipientAccountId().stream()
                    .map(RecipientReference::account)
                    .collect(Collectors.toSet()))
            .snapshot(
                new ProfileManagerInvitationReceivedSnapshot(
                    profileNameOf(invitation.profileId()), Optional.empty()))
            .at(change.changedAt())
            .subjectExpiresAt(Optional.of(invitation.expiresAt()))
            .build());
  }

  // Every write after the issue settles the request as the status it left the invitation in says,
  // at the instant the write recorded, whether or not the invitation names an Account. A request
  // that an earlier write settled stays as that write left it.
  private void resolveRequest(ProfileManagerInvitationChangedEvent change) {
    var invitation = change.invitation();
    var settled = Settlement.of(invitation.status());
    events.publishEvent(
        ResolveRequestsEvent.builder()
            .kind(ProfileManagementNotificationKinds.PROFILE_MANAGER_INVITATION_RECEIVED)
            .subject(SubjectReference.profileManagerInvitation(invitation.invitationId()))
            .resolution(settled.resolution())
            .answer(settled.answer().<RequestAnswer>map(answer -> answer))
            .at(change.changedAt())
            .build());
  }

  // The answer settles the request and tells the Account that issued the invitation, which still
  // manages the Profile: a manager who leaves it invalidates the invitations it sent while they
  // wait, and an acceptance that finds the inviter gone invalidates the invitation instead.
  private void answer(ProfileManagerInvitationChangedEvent change) {
    resolveRequest(change);
    Settlement.of(change.invitation().status())
        .answer()
        .ifPresent(answer -> tellAnswer(change, answer));
  }

  private void tellAnswer(
      ProfileManagerInvitationChangedEvent change, ProfileManagerInvitationAnswer answer) {
    var invitation = change.invitation();
    events.publishEvent(
        TellNotificationEvent.builder()
            .kind(ProfileManagementNotificationKinds.PROFILE_MANAGER_INVITATION_ANSWERED)
            .subject(SubjectReference.profileManagerInvitation(invitation.invitationId()))
            .recipients(Set.of(RecipientReference.account(invitation.inviterAccountId())))
            .snapshot(
                new ProfileManagerInvitationAnsweredSnapshot(
                    profileNameOf(invitation.profileId()), answer))
            .at(change.changedAt())
            .build());
  }

  private String profileNameOf(UUID profileId) {
    return profileRepository
        .findNameById(profileId)
        .orElseThrow(
            () -> new IllegalStateException("The Profile %s does not exist".formatted(profileId)));
  }

  /** How an invitation's request resolves from the status a write after the issue left. */
  private record Settlement(
      RequestResolution resolution, Optional<ProfileManagerInvitationAnswer> answer) {

    // A write after the issue leaves an invitation pending only when it invalidates the invitation
    // for its Account alone, which may no longer answer it although the Profile's managers still
    // read it as waiting.
    static Settlement of(ProfileManagerInvitationStatus status) {
      return switch (status) {
        case ACCEPTED -> decided(ProfileManagerInvitationAnswer.ACCEPTED);
        case DECLINED -> decided(ProfileManagerInvitationAnswer.DECLINED);
        case CANCELED, INVALIDATED, PENDING -> unanswered(RequestResolution.WITHDRAWN);
        case EXPIRED -> unanswered(RequestResolution.EXPIRED);
      };
    }

    private static Settlement decided(ProfileManagerInvitationAnswer answer) {
      return new Settlement(RequestResolution.DECIDED, Optional.of(answer));
    }

    private static Settlement unanswered(RequestResolution resolution) {
      return new Settlement(resolution, Optional.empty());
    }
  }
}
