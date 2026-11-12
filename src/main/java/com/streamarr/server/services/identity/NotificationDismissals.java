package com.streamarr.server.services.identity;

import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.DismissalSelector;
import com.streamarr.server.services.events.notification.SubjectReference;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Asks the Notifications module to dismiss what an identity change takes away (ADR 0063
 * §Dismissal). The identity domain owns each reason and works out each selector from its own
 * records, inside the transaction of the change, so the dismissal commits or rolls back with it.
 */
@Component
@RequiredArgsConstructor
class NotificationDismissals {

  private final ProfileManagerInvitationRepository managerInvitationRepository;
  private final ApplicationEventPublisher events;

  /**
   * Everything said about a Profile about to be deleted, and about its ProfileManager invitations,
   * for every recipient: nothing of a deleted Profile stays, its name in an old Notification
   * included. The invitations keep their rows, so they are read before the Profile goes. Its shares
   * are dismissed as each is deleted with it.
   */
  void profileDeleted(UUID profileId) {
    var subjects = new HashSet<SubjectReference>();
    subjects.add(SubjectReference.profile(profileId));
    managerInvitationRepository.findIdsByProfileId(profileId).stream()
        .map(SubjectReference::profileManagerInvitation)
        .forEach(subjects::add);
    dismiss(DismissalSelector.bySubject(subjects).build());
  }

  private void dismiss(DismissalSelector selector) {
    events.publishEvent(new DismissNotificationsEvent(selector));
  }
}
