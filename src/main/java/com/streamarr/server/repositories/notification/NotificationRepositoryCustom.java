package com.streamarr.server.repositories.notification;

import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.ShareSides;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The writes and the retention of Notifications about a Profile share (ADR 0063). */
public interface NotificationRepositoryCustom {

  /**
   * Up to the seek's limit of the recipient's Notifications beyond its position, newest latest
   * event first and ties by id in the same direction, nearest first.
   */
  List<Row<Notification>> rowsOfRecipient(UUID recipientAccountId, Seek seek);

  /** Stores a new Notification for its recipient, unread, and answers its id. */
  UUID insert(NotificationDraft<NotificationKind, ProfileShareFacts> draft);

  /**
   * The rule of one unread news per recipient, kind and share, in one statement: stores new unread
   * news, or moves the recipient's unread news of the kind about the share to the event's time when
   * that is later, and holds it until the transaction ends. Two events at the same moment update
   * one Notification.
   */
  UnreadNews<ProfileShareFacts> upsertUnreadNews(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft);

  /** Stores the facts on a Notification that this transaction already holds. */
  void replaceFacts(UUID notificationId, ProfileShareFacts facts);

  /**
   * The share as it is stored now, with its Profile's current name and the Accounts on each of its
   * sides, read past Hibernate's first-level cache; empty when the share no longer exists.
   */
  Optional<ShareSides> findShareSides(UUID shareId);

  /**
   * Deletes the history whose latest event is before the cutoff: the news that its recipient read
   * and the requests whose offer no longer waits at {@code now}. Answers how many it deleted.
   */
  int deleteHistoryBefore(Instant cutoff, Instant now);

  /** The unread news whose latest event is before the cutoff. */
  List<GuardedNotification> findUnreadNewsBefore(Instant cutoff);

  /**
   * Deletes the given Notifications that are still unread news with a latest event before the
   * cutoff, so a later event keeps the news it moves. Answers how many it deleted.
   */
  int deleteUnreadNewsBefore(Collection<UUID> notificationIds, Instant cutoff);
}
