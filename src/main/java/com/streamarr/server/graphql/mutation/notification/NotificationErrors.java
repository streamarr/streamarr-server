package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.notification.MarkAllNotificationsReadRejection;
import com.streamarr.server.services.notification.MarkNotificationReadRejection;
import com.streamarr.server.services.serversettings.ChangeNotificationRetentionRejection;
import com.streamarr.server.services.serversettings.NotificationRetentionSetting;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class NotificationErrors {

  private static final String NOTIFICATION_ID = "notificationId";

  private NotificationErrors() {}

  public static MarkNotificationReadError toMarkReadError(MarkNotificationReadRejection rejection) {
    return switch (rejection) {
      case MarkNotificationReadRejection.NotFound _ ->
          new MarkNotificationReadError.NotificationNotFoundError(
              "No such Notification.", InputPath.of(NOTIFICATION_ID));
    };
  }

  public static MarkNotificationReadError invalidNotificationId() {
    return new MarkNotificationReadError.InvalidIdError(
        "Enter a valid ID.", InputPath.of(NOTIFICATION_ID));
  }

  public static MarkAllNotificationsReadError toMarkAllReadError(
      MarkAllNotificationsReadRejection rejection) {
    return switch (rejection) {
      case MarkAllNotificationsReadRejection.UnreadablePosition _ ->
          new MarkAllNotificationsReadError.InvalidCursorError(
              "Pass a cursor from the notifications list unchanged.", InputPath.of("through"));
    };
  }

  public static ChangeNotificationRetentionError toChangeRetentionError(
      ChangeNotificationRetentionRejection rejection) {
    return switch (rejection) {
      case ChangeNotificationRetentionRejection.OutOfRange _ ->
          new ChangeNotificationRetentionError.NotificationRetentionOutOfRangeError(
              "Choose from %d to %d days."
                  .formatted(
                      NotificationRetentionSetting.MINIMUM_DAYS,
                      NotificationRetentionSetting.MAXIMUM_DAYS),
              InputPath.of("days"));
    };
  }
}
