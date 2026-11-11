package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.services.serversettings.NotificationRetentionSetting;
import java.util.List;
import java.util.Optional;

public record ChangeNotificationRetentionPayload(
    Optional<NotificationRetentionSetting> notificationRetentionSetting,
    List<ChangeNotificationRetentionError> userErrors) {}
