package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.ShareNotification;
import com.streamarr.server.domain.pagination.PaginationDirection;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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

  private final AuthorizationService authorizationService;
  private final NotificationsList notificationsList;
  private final ShareNotifications shareNotifications;
  private final NotificationRepository notificationRepository;
  private final Clock clock;

  /** The Account's Notifications, newest latest event first. */
  @Transactional(readOnly = true)
  public ListPage<ShareNotification> notifications(
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

  /** Marks one of the Account's Notifications read; marking a read one again changes nothing. */
  @Transactional
  public Outcome<NotificationRead, MarkNotificationReadRejection> markNotificationRead(
      @NonNull AuthenticatedIdentity identity, @NonNull UUID notificationId) {
    authorizationService.requireAllowed(identity, new Intent.MarkNotificationsRead());
    var shown =
        notificationRepository
            .findById(notificationId)
            .filter(
                notification -> notification.getRecipientAccountId().equals(identity.accountId()))
            .flatMap(
                notification ->
                    shareNotifications.shown(List.of(notification)).stream().findFirst());
    if (shown.isEmpty()) {
      return Outcome.rejected(new MarkNotificationReadRejection.NotFound());
    }

    var notification = shown.orElseThrow().notification();
    if (notification.getReadAt().isEmpty()) {
      notification.setReadAt(clock.instant());
      notificationRepository.saveAndFlush(notification);
    }

    return Outcome.accepted(
        new NotificationRead(shown.orElseThrow(), unreadCountOf(identity.accountId())));
  }

  /**
   * Marks read every unread Notification of the Account at or below the position, and answers the
   * Account's unread count after it. An empty position is one the client's cursor did not name.
   */
  @Transactional
  public Outcome<Integer, MarkAllNotificationsReadRejection> markAllNotificationsRead(
      @NonNull AuthenticatedIdentity identity, @NonNull Optional<Position> through) {
    authorizationService.requireAllowed(identity, new Intent.MarkNotificationsRead());
    if (through.isEmpty()) {
      return Outcome.rejected(new MarkAllNotificationsReadRejection.UnreadablePosition());
    }

    var now = clock.instant();
    var accountId = identity.accountId();
    var atOrBelow =
        Seek.builder()
            .order(NotificationsList.NEWEST_FIRST)
            .position(through)
            .direction(PaginationDirection.FORWARD)
            .limit(1)
            .includingPosition(true)
            .build();
    var shown =
        shareNotifications.shown(
            notificationRepository.findUnreadOfRecipientThrough(accountId, atOrBelow, now));
    notificationRepository.markReadThrough(
        shown.stream().map(notification -> notification.notification().getId()).toList(),
        accountId,
        atOrBelow,
        now);
    return Outcome.accepted(unreadCountOf(accountId));
  }

  private int unreadCountOf(UUID accountId) {
    return shareNotifications
        .shown(notificationRepository.findUnreadOfRecipient(accountId, clock.instant()))
        .size();
  }
}
