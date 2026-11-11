package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.ShareNotification;
import lombok.NonNull;

/** A Notification that its recipient marked read, and the recipient's unread count after it. */
public record NotificationRead(@NonNull ShareNotification notification, int unreadCount) {}
