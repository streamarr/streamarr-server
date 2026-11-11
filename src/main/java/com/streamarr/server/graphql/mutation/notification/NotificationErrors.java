package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.notification.MarkNotificationsReadRejection;
import com.streamarr.server.services.serversettings.ChangeNotificationRetentionRejection;
import com.streamarr.server.services.serversettings.NotificationRetentionSetting;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class NotificationErrors {

  private static final String IDS = "ids";

  private NotificationErrors() {}

  public static MarkNotificationsReadError toMarkReadError(
      MarkNotificationsReadRejection rejection) {
    return switch (rejection) {
      case MarkNotificationsReadRejection.IdCountOutOfRange(var minimum, var maximum) ->
          new MarkNotificationsReadError.NotificationIdCountOutOfRangeError(
              "Pass from %d to %d ids.".formatted(minimum, maximum), InputPath.of(IDS));
    };
  }

  /** The id at the index of the input's list is not an id. */
  public static MarkNotificationsReadError invalidNotificationId(int index) {
    return new MarkNotificationsReadError.InvalidIdError(
        "Enter a valid ID.", InputPath.element(IDS, index));
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
