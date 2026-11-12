package com.streamarr.server.repositories.notification;

import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.services.events.notification.DismissalSelector;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.StoredNotification;
import com.streamarr.server.services.notification.events.ChangedNotification;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The Notifications module's own table (ADR 0063 §How the server builds it). Every write runs in
 * the caller's transaction, touches no other table, and answers the Notifications it changed, apart
 * from the deletions that are pushed to nobody: a deleted recipient's and retention's. Every read
 * selects one recipient's rows and looks at nothing else.
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

  /**
   * Settles the requests of the kind about the subject that no event has resolved yet. The
   * resolution's instant is compared with each request's stored expiry, never with the time it is
   * handled: before the expiry, the request takes the resolution, its instant and a decision's
   * answer; at or after it, the request expired, at its expiry. A request keeps its time and its
   * read state.
   */
  List<ChangedNotification> resolve(ResolveRequestsEvent resolution);

  /** Deletes the Notifications the selector selects, for every recipient it names or all. */
  List<ChangedNotification> dismiss(DismissalSelector selector);

  /** Deletes every Notification of the recipient. */
  void deleteOfRecipient(RecipientReference recipient);

  /**
   * Deletes up to {@code limit} Notifications of history whose latest event is before the cutoff,
   * and answers how many: news that its recipient read, and requests that an event resolved or that
   * read as expired at the instant. The delete states history and age in its own condition.
   */
  int deleteHistoryBefore(Instant cutoff, Instant now, int limit);

  /**
   * Up to the seek's limit of the recipient's Notifications beyond its position, newest latest
   * event first and ties by id in the same direction, nearest first.
   */
  List<Row<StoredNotification>> rowsOfRecipient(RecipientReference recipient, Seek seek);

  /** Those of the given Notifications that the recipient has, each at its place in the list. */
  List<Row<StoredNotification>> rowsOfRecipientWithIds(
      RecipientReference recipient, Collection<UUID> notificationIds);

  /**
   * How many of the recipient's Notifications are unread at the instant: news it has not read, and
   * requests it has not read that no event resolved and whose expiry has not passed.
   */
  int countUnread(RecipientReference recipient, Instant now);

  /**
   * Marks read at the instant those of the given Notifications that are the recipient's and unread
   * then, writing nothing else, so an update in place that commits first keeps what it stored.
   */
  List<ChangedNotification> markRead(
      RecipientReference recipient, Collection<UUID> notificationIds, Instant now);
}
