package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ManagerInvitationAction;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.events.auth.ProfileManagerInvitationChangedEvent;
import com.streamarr.server.services.events.notification.ProfileManagementNotificationKinds;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationReceivedSnapshot;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the Account that an invitation to manage a Profile names that it may accept it (ADR 0063
 * §The Profile management kinds). It hears each invitation write inside the write's transaction, so
 * what it tells commits or rolls back with the write. An invitation names an Account only when that
 * Account may accept it, which the invite reads and holds until it commits, and only a manager
 * invites, so the Account that acted is never the one told.
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
      case ManagerInvitationAction.Answered _,
          ManagerInvitationAction.Canceled _,
          ManagerInvitationAction.StoppedWaiting _,
          ManagerInvitationAction.InvalidatedForItsAccount _ -> {}
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
                    profileNameOf(invitation), Optional.empty()))
            .at(change.changedAt())
            .subjectExpiresAt(Optional.of(invitation.expiresAt()))
            .build());
  }

  private String profileNameOf(ChangedManagerInvitation invitation) {
    return profileRepository
        .findNameById(invitation.profileId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "The invited Profile %s does not exist".formatted(invitation.profileId())));
  }
}
