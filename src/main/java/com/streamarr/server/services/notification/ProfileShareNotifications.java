package com.streamarr.server.services.notification;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareAction;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.ShareSides;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
import com.streamarr.server.services.notification.events.NotificationsReadEvent;
import com.streamarr.server.services.notification.events.ReadNotification;
import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Makes the sharing kinds' Notifications (ADR 0063 §The sharing kinds) from each share write, in
 * the write's own transaction, so a Notification commits or rolls back with the change that made
 * it. Recipients come from the share's sides as they stand at the write; the Account that acted is
 * never told. Withdrawn and expired offers tell nobody: their requests read the resolution from the
 * share.
 */
@Component
class ProfileShareNotifications {

  private final NotificationRepository notificationRepository;
  private final NotificationWriter<NotificationKind, ProfileShareFacts> writer;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  ProfileShareNotifications(
      NotificationRepository notificationRepository,
      ApplicationEventPublisher events,
      Clock clock) {
    this.notificationRepository = notificationRepository;
    this.writer =
        new NotificationWriter<>(
            new ProfileShareKinds(),
            new ProfileShareNotificationStore(notificationRepository),
            events);
    this.events = events;
    this.clock = clock;
  }

  @EventListener
  public void onProfileShareChanged(ProfileShareChangedEvent change) {
    var shareId = change.share().shareId();
    switch (change.action()) {
      case ShareAction.Offered() -> sidesOf(shareId).ifPresent(sides -> tellOffer(shareId, sides));
      case ShareAction.Answered(var accountId) ->
          sidesOf(shareId)
              .ifPresent(
                  sides -> {
                    tellAnswer(shareId, sides, accountId);
                    resolveRequests(shareId);
                  });
      case ShareAction.EndedBySide(var accountId) ->
          sidesOf(shareId).ifPresent(sides -> tellEndBySide(shareId, sides, accountId));
      case ShareAction.EndedByServer(var accountId) ->
          sidesOf(shareId).ifPresent(sides -> tellEndByServer(shareId, sides, accountId));
      case ShareAction.OfferStoppedWaiting() -> resolveRequests(shareId);
      case ShareAction.Other(), ShareAction.EndedWithHousehold() -> {}
    }
  }

  // A request reads its resolution from the share, so resolving it writes nothing: its recipients'
  // open screens are told after commit.
  private void resolveRequests(UUID shareId) {
    var requests = notificationRepository.findRequestsAbout(shareId);
    if (requests.isEmpty()) {
      return;
    }

    events.publishEvent(
        new NotificationsReadEvent(
            requests.stream()
                .map(
                    request ->
                        new ReadNotification(
                            request.notificationId(),
                            request.recipientAccountId(),
                            ReadNotification.Read.RESOLVED))
                .toList()));
  }

  private Optional<ShareSides> sidesOf(UUID shareId) {
    return notificationRepository.findShareSides(shareId);
  }

  // The share records the Account that made the offer; an automatic re-offer is made in the name
  // of the Account invitation's issuer.
  private void tellOffer(UUID shareId, ShareSides sides) {
    writer.tell(
        about(shareId)
            .kind(NotificationKind.PROFILE_SHARE_OFFERED)
            .facts(ProfileShareFacts.offered(sides.profileName()))
            .recipients(sides.householdSide())
            .actingAccountId(sides.offeredByAccountId())
            .build());
  }

  // A rejection that came after the offer's time ran out is stored as expired, and nobody is told.
  private void tellAnswer(UUID shareId, ShareSides sides, UUID answeredBy) {
    answerOf(sides.status())
        .ifPresent(
            answer ->
                writer.tell(
                    about(shareId)
                        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
                        .facts(ProfileShareFacts.answered(sides.profileName(), answer))
                        .recipients(sides.offeringSide())
                        .actingAccountId(Optional.of(answeredBy))
                        .build()));
  }

  private static Optional<ProfileShareAnswer> answerOf(ProfileShareStatus status) {
    return switch (status) {
      case ACTIVE -> Optional.of(ProfileShareAnswer.ACCEPTED);
      case REJECTED -> Optional.of(ProfileShareAnswer.REJECTED);
      case PENDING, ENDED, CANCELED, EXPIRED, INVALIDATED -> Optional.empty();
    };
  }

  // An Account that administers the Household ends the share for the Household's side, even when
  // it also manages the Profile.
  private void tellEndBySide(UUID shareId, ShareSides sides, UUID endedBy) {
    var side =
        sides.householdSide().contains(endedBy)
            ? ProfileShareEndedBy.HOUSEHOLD_SIDE
            : ProfileShareEndedBy.OFFERING_SIDE;
    tellEnd(about(shareId).actingAccountId(Optional.of(endedBy)), sides, side);
  }

  private void tellEndByServer(UUID shareId, ShareSides sides, UUID endedBy) {
    tellEnd(
        about(shareId).actingAccountId(Optional.of(endedBy)), sides, ProfileShareEndedBy.SERVER);
  }

  private void tellEnd(
      NotificationEvent.NotificationEventBuilder<NotificationKind, ProfileShareFacts> end,
      ShareSides sides,
      ProfileShareEndedBy endedBy) {
    writer.tell(
        end.kind(NotificationKind.PROFILE_SHARE_ENDED)
            .facts(ProfileShareFacts.ended(sides.profileName(), endedBy))
            .recipients(toldOfEnd(sides, endedBy))
            .build());
  }

  /** The side that did not end the share, or both sides when the server ended it. */
  private static Set<UUID> toldOfEnd(ShareSides sides, ProfileShareEndedBy endedBy) {
    return switch (endedBy) {
      case HOUSEHOLD_SIDE -> sides.offeringSide();
      case OFFERING_SIDE -> sides.householdSide();
      case SERVER -> {
        var bothSides = new HashSet<>(sides.offeringSide());
        bothSides.addAll(sides.householdSide());
        yield bothSides;
      }
    };
  }

  private NotificationEvent.NotificationEventBuilder<NotificationKind, ProfileShareFacts> about(
      UUID shareId) {
    return NotificationEvent.<NotificationKind, ProfileShareFacts>builder()
        .subjectId(shareId)
        .at(clock.instant());
  }
}
