package com.streamarr.server.repositories.notification;

import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.events.ChangedNotification;
import java.util.List;

/**
 * The Notifications module's own table (ADR 0063 §How the server builds it). Every write runs in
 * the caller's transaction, touches no other table, and answers the Notifications it changed.
 */
public interface NotificationRepository {

  /**
   * Tells news to each recipient under the noise rule, in one statement: makes the recipient's
   * unread news of the kind about the subject, or updates the one it has in place, keeping its
   * identity. The later event's snapshot and time win; an earlier event that arrives late changes
   * nothing. Two tells at the same moment update one Notification.
   */
  List<ChangedNotification> tellNews(TellNotificationEvent tell);

  /**
   * Makes a waiting request for each recipient, with the subject's expiry the tell carries. A
   * request never updates in place: a recipient who already has the request about the subject gets
   * no second one, and nothing of the first changes.
   */
  List<ChangedNotification> tellRequests(TellNotificationEvent tell);
}
