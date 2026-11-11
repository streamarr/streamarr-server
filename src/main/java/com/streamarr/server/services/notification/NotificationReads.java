package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationSubject;
import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Every read of stored Notifications goes through here: the list, the unread count, the marks, live
 * changes and retention. Each Notification is read and guarded by the family of its kind's subject,
 * so they all agree on what a recipient sees.
 */
@Component
@RequiredArgsConstructor
class NotificationReads {

  private final ShareNotifications profileShares;

  /**
   * The Notifications that the guard shows their recipients, each with its subject, in the order
   * given.
   *
   * @throws AuthorizationUnavailableException when the guard cannot decide a Notification
   */
  List<SubjectNotification> shown(List<Notification> notifications) {
    var shown = new HashMap<UUID, SubjectNotification>();
    bySubject(notifications)
        .forEach(
            (subject, ofSubject) ->
                familyOf(subject)
                    .shown(ofSubject)
                    .forEach(read -> shown.put(read.notification().getId(), read)));
    return notifications.stream()
        .flatMap(notification -> Optional.ofNullable(shown.get(notification.getId())).stream())
        .toList();
  }

  /** What the guard decides for each Notification whose subject still exists, keyed by its id. */
  Map<UUID, NotificationVisibility> visibilities(List<Notification> notifications) {
    var visibilities = new HashMap<UUID, NotificationVisibility>();
    bySubject(notifications)
        .forEach(
            (subject, ofSubject) -> visibilities.putAll(familyOf(subject).visibilities(ofSubject)));
    return visibilities;
  }

  private static Map<NotificationSubject, List<Notification>> bySubject(
      List<Notification> notifications) {
    return notifications.stream()
        .collect(
            Collectors.groupingBy(
                notification -> notification.getKind().getSubject(),
                () -> new EnumMap<>(NotificationSubject.class),
                Collectors.toList()));
  }

  private NotificationSubjects familyOf(NotificationSubject subject) {
    return switch (subject) {
      case PROFILE_SHARE -> profileShares;
    };
  }
}
