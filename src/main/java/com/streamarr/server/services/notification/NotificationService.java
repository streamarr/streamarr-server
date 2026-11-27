package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.StoredNotification;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.time.Clock;
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
 * mark is refused to a device-bound session, and looks at the Account's own Notifications alone:
 * nothing decides again what the Account was told.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private static final int FEWEST_MARKED = 1;
  private static final int MOST_MARKED = 100;

  private final AuthorizationService authorizationService;
  private final NotificationsList notificationsList;
  private final NotificationRepository notificationRepository;
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
  public ListPage<StoredNotification> notifications(
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
   * Marks read those of the given Notifications that are the Account's and unread, and answers the
   * Account's unread count after it. Every other id is skipped alike, so the answer never tells
   * which Notifications exist.
   */
  @Transactional
  public Outcome<Integer, MarkNotificationsReadRejection> markNotificationsRead(
      @NonNull AuthenticatedIdentity identity, @NonNull List<UUID> notificationIds) {
    authorizationService.requireAllowed(identity, new Intent.MarkNotificationsRead());
    if (notificationIds.size() < FEWEST_MARKED || notificationIds.size() > MOST_MARKED) {
      return Outcome.rejected(
          new MarkNotificationsReadRejection.IdCountOutOfRange(FEWEST_MARKED, MOST_MARKED));
    }

    var marked =
        notificationRepository.markRead(
            RecipientReference.account(identity.accountId()), notificationIds, clock.instant());
    if (!marked.isEmpty()) {
      events.publishEvent(new NotificationsChangedEvent(marked));
    }

    return Outcome.accepted(unreadCountOf(identity.accountId()));
  }

  /**
   * One Notification of a committed change as the Account reads it now, at its place in the list,
   * or empty once it no longer exists. It decides nothing: the Account's access to its
   * Notifications was decided when it began to follow them.
   */
  @Transactional(readOnly = true)
  public Optional<Row<StoredNotification>> changedNotification(
      @NonNull AuthenticatedIdentity identity, @NonNull UUID notificationId) {
    return notificationRepository
        .rowsOfRecipientWithIds(
            RecipientReference.account(identity.accountId()), List.of(notificationId))
        .stream()
        .findFirst();
  }

  /**
   * The Account's unread count after a committed change. It decides nothing: the Account's access
   * to its Notifications was decided when it began to follow them.
   */
  @Transactional(readOnly = true)
  public int unreadCountAfterChange(@NonNull AuthenticatedIdentity identity) {
    return unreadCountOf(identity.accountId());
  }

  private int unreadCountOf(UUID accountId) {
    return notificationRepository.countUnread(
        RecipientReference.account(accountId), clock.instant());
  }
}
