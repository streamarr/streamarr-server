package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.SubjectNotification;
import lombok.NonNull;

/** A Notification that its recipient marked read, and the recipient's unread count after it. */
public record NotificationRead(@NonNull SubjectNotification notification, int unreadCount) {}
