package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.serversettings.ChangeNotificationRetentionRejection;
import com.streamarr.server.services.serversettings.NotificationRetentionSetting;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class NotificationErrors {

  private NotificationErrors() {}

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
