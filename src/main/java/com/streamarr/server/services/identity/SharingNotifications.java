package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ShareAction;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the sides of a Profile share what happened to it (ADR 0063 §The sharing kinds). It hears
 * each share write inside the write's transaction, so what it tells commits or rolls back with the
 * write. It works out the recipients from the relationship rows as they stand at the write and
 * holds those rows until the write commits, so a relationship that changes at the same moment
 * either waits for the write or has already changed when it is read. The Account that acted is
 * never told.
 */
@Component
@RequiredArgsConstructor
class SharingNotifications {

  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ApplicationEventPublisher events;

  @EventListener
  public void onProfileShareChanged(ProfileShareChangedEvent change) {
    switch (change.action()) {
      case ShareAction.Offered() -> tellOffer(change);
      case ShareAction.Answered _,
          ShareAction.OfferStoppedWaiting _,
          ShareAction.Ending _,
          ShareAction.Other _ -> {}
    }
  }

  // The share records the Account that made the offer; an automatic re-offer is made in the name
  // of the Account invitation's issuer.
  private void tellOffer(ProfileShareChangedEvent change) {
    var share = change.share();
    var recipients = householdSide(share);
    share.offeredByAccountId().ifPresent(recipients::remove);
    events.publishEvent(
        TellNotificationEvent.builder()
            .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
            .subject(SubjectReference.profileShare(share.shareId()))
            .recipients(accounts(recipients))
            .snapshot(new ProfileShareOfferedSnapshot(profileNameOf(share), Optional.empty()))
            .at(instantOf(change))
            .subjectExpiresAt(share.expiresAt())
            .build());
  }

  /** The target Household's HouseholdAdmins, held until the write commits. */
  private Set<UUID> householdSide(ChangedShare share) {
    return new HashSet<>(userAccountRepository.lockHouseholdAdminIds(share.householdId()));
  }

  private String profileNameOf(ChangedShare share) {
    return profileRepository
        .findNameById(share.profileId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "The shared Profile %s does not exist".formatted(share.profileId())));
  }

  // A write that tells anyone took the instant it records on the share.
  private static Instant instantOf(ProfileShareChangedEvent change) {
    return change
        .changedAt()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Share change %s took no instant".formatted(change.share().shareId())));
  }

  private static Set<RecipientReference> accounts(Set<UUID> accountIds) {
    return accountIds.stream().map(RecipientReference::account).collect(Collectors.toSet());
  }
}
