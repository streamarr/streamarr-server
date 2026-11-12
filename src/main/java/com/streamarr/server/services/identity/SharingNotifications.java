package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareAction;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.domain.notification.RequestAnswer;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.DismissalSelector;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareEndedSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
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
 * Tells the sides of a Profile share what happened to it, settles the requests about an offer when
 * it stops waiting, and dismisses everything said about a share that is deleted (ADR 0063 §The
 * sharing kinds, §Dismissal). It hears each share write inside the write's transaction, so what it
 * tells commits or rolls back with the write. It works out the recipients from the relationship
 * rows as they stand at the write and holds those rows until the write commits, so a relationship
 * that changes at the same moment either waits for the write or has already changed when it is
 * read. The Account that acted is never told.
 */
@Component
@RequiredArgsConstructor
class SharingNotifications {

  private final UserAccountRepository userAccountRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileRepository profileRepository;
  private final ApplicationEventPublisher events;

  @EventListener
  public void onProfileShareChanged(ProfileShareChangedEvent change) {
    if (change.removal()) {
      dismissEverythingAbout(change.share());
      return;
    }

    switch (change.action()) {
      case ShareAction.Offered() -> tellOffer(change);
      case ShareAction.Answered(var answeredBy) -> answer(change, answeredBy);
      case ShareAction.OfferStoppedWaiting _ -> resolveRequests(change);
      case ShareAction.EndedBySide(var endedBy) ->
          tellEnd(change, endedBy, sideThatEnded(change.share(), endedBy));
      case ShareAction.EndedByServer(var endedBy) ->
          tellEnd(change, endedBy, ProfileShareEndedBy.SERVER);
      case ShareAction.EndedWithHousehold _, ShareAction.Other _ -> {}
    }
  }

  // A share deleted with its Profile or its Household leaves nothing to link to, so every
  // recipient loses what it was told about it.
  private void dismissEverythingAbout(ChangedShare share) {
    events.publishEvent(
        new DismissNotificationsEvent(
            DismissalSelector.bySubject(Set.of(SubjectReference.profileShare(share.shareId())))
                .build()));
  }

  // The share records the Account that made the offer; an automatic re-offer is made in the name
  // of the Account invitation's issuer.
  private void tellOffer(ProfileShareChangedEvent change) {
    var share = change.share();
    var recipients = householdSide(share);
    share.offeredByAccountId().ifPresent(recipients::remove);
    events.publishEvent(
        tellAbout(change, recipients)
            .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
            .snapshot(new ProfileShareOfferedSnapshot(profileNameOf(share), Optional.empty()))
            .subjectExpiresAt(share.expiresAt())
            .build());
  }

  // An offer that stopped waiting settles every recipient's request at once, as the status the
  // write left it in says, at the instant the write recorded.
  private void resolveRequests(ProfileShareChangedEvent change) {
    var share = change.share();
    Settlement.of(share.status())
        .map(
            settled ->
                ResolveRequestsEvent.builder()
                    .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
                    .subject(SubjectReference.profileShare(share.shareId()))
                    .resolution(settled.resolution())
                    .answer(settled.answer().<RequestAnswer>map(answer -> answer))
                    .at(instantOf(change))
                    .build())
        .ifPresent(events::publishEvent);
  }

  // An answer that finds the offer's time run out, or its offerer no longer allowed to offer,
  // stores no answer, so nobody is told.
  private void answer(ProfileShareChangedEvent change, UUID answeredBy) {
    resolveRequests(change);
    Settlement.of(change.share().status())
        .flatMap(Settlement::answer)
        .ifPresent(answer -> tellAnswer(change, answer, answeredBy));
  }

  private void tellAnswer(
      ProfileShareChangedEvent change, ProfileShareAnswer answer, UUID answeredBy) {
    var told = offeringSide(change.share());
    told.remove(answeredBy);
    events.publishEvent(
        tellAbout(change, told)
            .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
            .snapshot(new ProfileShareAnsweredSnapshot(profileNameOf(change.share()), answer))
            .build());
  }

  // An Account that administers the Household ends the share for the Household's side, even when
  // it also manages the Profile.
  private ProfileShareEndedBy sideThatEnded(ChangedShare share, UUID endedBy) {
    return householdSide(share).contains(endedBy)
        ? ProfileShareEndedBy.HOUSEHOLD_SIDE
        : ProfileShareEndedBy.OFFERING_SIDE;
  }

  private void tellEnd(ProfileShareChangedEvent change, UUID endedBy, ProfileShareEndedBy side) {
    var told = toldOfEnd(change.share(), side);
    told.remove(endedBy);
    events.publishEvent(
        tellAbout(change, told)
            .kind(SharingNotificationKinds.PROFILE_SHARE_ENDED)
            .snapshot(new ProfileShareEndedSnapshot(profileNameOf(change.share()), side))
            .build());
  }

  /**
   * The side that did not end the share, or both sides when the server ended it. An Account on both
   * sides is one recipient, so it is told once.
   */
  private Set<UUID> toldOfEnd(ChangedShare share, ProfileShareEndedBy endedBy) {
    return switch (endedBy) {
      case HOUSEHOLD_SIDE -> offeringSide(share);
      case OFFERING_SIDE -> householdSide(share);
      case SERVER -> bothSides(share);
    };
  }

  private Set<UUID> bothSides(ChangedShare share) {
    var both = householdSide(share);
    both.addAll(offeringSide(share));
    return both;
  }

  private TellNotificationEvent.TellNotificationEventBuilder tellAbout(
      ProfileShareChangedEvent change, Set<UUID> told) {
    return TellNotificationEvent.builder()
        .subject(SubjectReference.profileShare(change.share().shareId()))
        .recipients(accounts(told))
        .at(instantOf(change));
  }

  /** The target Household's HouseholdAdmins, held until the write commits. */
  private Set<UUID> householdSide(ChangedShare share) {
    return new HashSet<>(userAccountRepository.lockHouseholdAdminIds(share.householdId()));
  }

  /**
   * The Profile's ProfileManagers, direct and self-managing, and the Account that made the offer
   * while it may still read the share, which a ServerAdmin may, all held until the write commits.
   * An offerer that has stopped managing the Profile and is not a ServerAdmin is no longer on it.
   */
  private Set<UUID> offeringSide(ChangedShare share) {
    var side = new HashSet<>(profileManagerRepository.lockManagerIds(share.profileId()));
    userAccountRepository.lockSelfManagerId(share.profileId()).ifPresent(side::add);
    share.offeredByAccountId().filter(this::isServerAdmin).ifPresent(side::add);
    return side;
  }

  private boolean isServerAdmin(UUID accountId) {
    return userAccountRepository
        .lockAuthorityFacts(accountId)
        .map(AccountAuthorityFacts::serverAdmin)
        .orElse(false);
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

  /**
   * How an offer's requests resolve from the status that stopped it waiting. An offer that linking
   * made its Profile's home share is accepted: the share is active, so a withdrawn request beside
   * it would contradict the share queries.
   */
  private record Settlement(RequestResolution resolution, Optional<ProfileShareAnswer> answer) {

    static Optional<Settlement> of(ProfileShareStatus status) {
      return switch (status) {
        case ACTIVE -> decided(ProfileShareAnswer.ACCEPTED);
        case REJECTED -> decided(ProfileShareAnswer.REJECTED);
        case CANCELED, INVALIDATED -> unanswered(RequestResolution.WITHDRAWN);
        case EXPIRED -> unanswered(RequestResolution.EXPIRED);
        case PENDING, ENDED -> Optional.empty();
      };
    }

    private static Optional<Settlement> decided(ProfileShareAnswer answer) {
      return Optional.of(new Settlement(RequestResolution.DECIDED, Optional.of(answer)));
    }

    private static Optional<Settlement> unanswered(RequestResolution resolution) {
      return Optional.of(new Settlement(resolution, Optional.empty()));
    }
  }
}
