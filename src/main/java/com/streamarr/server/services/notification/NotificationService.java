package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.repositories.notification.ShareNotificationRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.notification.events.NotificationsReadEvent;
import com.streamarr.server.services.notification.events.ReadNotification;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the signed-in Account reads and marks of its own Notifications (ADR 0063). Every read and
 * mark is refused to a device-bound session, and shows only the Notifications that the guard shows
 * the Account, decided from its stored relationships.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private static final int FEWEST_MARKED = 1;
  private static final int MOST_MARKED = 100;

  private final AuthorizationService authorizationService;
  private final NotificationsList notificationsList;
  private final NotificationReads notificationReads;
  private final ShareNotificationRepository notificationRepository;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  /** Refuses a session that may not follow the Account's Notifications. */
  public void requireViewNotifications(@NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewNotifications());
  }

  /**
   * Refuses a session that may not mark the Account's Notifications read, before anything it sent
   * is read, so a refused session learns nothing from its input.
   */
  public void requireMarkNotificationsRead(@NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.MarkNotificationsRead());
  }

  /** The Account's Notifications, newest latest event first. */
  @Transactional(readOnly = true)
  public ListPage<SubjectNotification> notifications(
      @NonNull AuthenticatedIdentity identity, @NonNull PageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewNotifications());
    return Pager.page(notificationsList, identity.accountId(), request);
  }

  /** How many of the Account's Notifications are unread, the waiting requests among them. */
  @Transactional(readOnly = true)
  public int unreadNotificationCount(@NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewNotifications());
    return unreadCountOf(identity.accountId());
  }

  /**
   * Marks read those of the given Notifications that are the Account's, that the guard shows it and
   * that are unread, and answers the Account's unread count after it. Every other id is skipped
   * alike, so the answer never tells which Notifications exist.
   */
  @Transactional
  public Outcome<Integer, MarkNotificationsReadRejection> markNotificationsRead(
      @NonNull AuthenticatedIdentity identity, @NonNull List<UUID> notificationIds) {
    authorizationService.requireAllowed(identity, new Intent.MarkNotificationsRead());
    if (notificationIds.size() < FEWEST_MARKED || notificationIds.size() > MOST_MARKED) {
      return Outcome.rejected(
          new MarkNotificationsReadRejection.IdCountOutOfRange(FEWEST_MARKED, MOST_MARKED));
    }

    var now = clock.instant();
    var accountId = identity.accountId();
    var shown =
        notificationReads.shown(
            notificationRepository.findUnreadOfRecipientWithIds(accountId, notificationIds, now));
    publishMarked(
        accountId,
        notificationRepository.markRead(
            shown.stream().map(notification -> notification.notification().getId()).toList(),
            accountId,
            now));
    return Outcome.accepted(unreadCountOf(accountId));
  }

  /**
   * Those of a change's Notifications that the guard shows the Account now, and its unread count
   * after the change. Empty when the guard shows it none of them, or none is still kept.
   */
  @Transactional(readOnly = true)
  public Optional<NotificationsShown> shownChange(
      @NonNull AuthenticatedIdentity identity, @NonNull Collection<UUID> notificationIds) {
    authorizationService.requireAllowed(identity, new Intent.ViewNotifications());
    var shown =
        notificationsList.shownOf(
            notificationRepository.rowsOfRecipientWithIds(identity.accountId(), notificationIds));
    if (shown.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(new NotificationsShown(shown, unreadCountOf(identity.accountId())));
  }

  private void publishMarked(UUID accountId, List<UUID> notificationIds) {
    if (notificationIds.isEmpty()) {
      return;
    }

    events.publishEvent(
        new NotificationsReadEvent(
            notificationIds.stream()
                .map(id -> new ReadNotification(id, accountId, ReadNotification.Read.MARKED))
                .toList()));
  }

  private int unreadCountOf(UUID accountId) {
    return notificationReads
        .shown(notificationRepository.findUnreadOfRecipient(accountId, clock.instant()))
        .size();
  }
}
