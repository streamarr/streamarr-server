package com.streamarr.server.services.identity;

import com.streamarr.server.domain.notification.DismissalSelector;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.NotificationRecipientDeletedEvent;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Asks the Notifications module to dismiss what an identity change takes away, and to delete a
 * deleted Account's own Notifications (ADR 0063 §Dismissal). The identity domain owns each reason
 * and works out each selector from its own records, inside the transaction of the change, so the
 * dismissal commits or rolls back with it.
 */
@Component
@RequiredArgsConstructor
class NotificationDismissals {

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileManagerInvitationRepository managerInvitationRepository;
  private final ApplicationEventPublisher events;

  /**
   * The Account lost the HouseholdAdmin role in the Household, by demotion or by moving away, so
   * the offers still pending into it are no longer its to decide: its requests about them that no
   * event has resolved go. Its news and its resolved requests stay, and so does a request whose
   * offer's time has run out, which reads as history.
   */
  void householdAdminRoleLost(UUID accountId, UUID householdId, Instant now) {
    dismissWaitingRequestsAboutOffersInto(householdId, accountId, now);
  }

  /**
   * The Account moved out of the Household. A HouseholdAdmin's waiting requests about the offers
   * still pending into it go as on a demotion; a HouseholdMember was told none, so the role that it
   * held there needs no reading.
   */
  void accountLeftHousehold(UUID accountId, UUID householdId, Instant now) {
    dismissWaitingRequestsAboutOffersInto(householdId, accountId, now);
  }

  private void dismissWaitingRequestsAboutOffersInto(
      UUID householdId, UUID accountId, Instant now) {
    var offers = shareRepository.findPendingOfferIdsInto(householdId, now);
    if (offers.isEmpty()) {
      return;
    }

    dismiss(
        DismissalSelector.bySubject(
                offers.stream().map(SubjectReference::profileShare).collect(Collectors.toSet()))
            .recipient(Optional.of(RecipientReference.account(accountId)))
            .kinds(Set.of(SharingNotificationKinds.PROFILE_SHARE_OFFERED))
            .form(Optional.of(DismissalSelector.DismissedForm.WAITING_REQUESTS))
            .build());
  }

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

  /**
   * Everything said about a Household about to be deleted, for every recipient. The shares into it
   * are dismissed as each is deleted with it.
   */
  void householdDeleted(UUID householdId) {
    dismiss(DismissalSelector.bySubject(Set.of(SubjectReference.household(householdId))).build());
  }

  /**
   * The Account is being deleted, so its own Notifications go with it. Nothing is pushed: its
   * sessions end with it, and no other Account's Notification holds its name.
   */
  void recipientDeleted(UUID accountId) {
    events.publishEvent(
        new NotificationRecipientDeletedEvent(RecipientReference.account(accountId)));
  }

  private void dismiss(DismissalSelector selector) {
    events.publishEvent(new DismissNotificationsEvent(selector));
  }
}
