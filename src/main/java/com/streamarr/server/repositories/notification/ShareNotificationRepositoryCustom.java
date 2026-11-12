package com.streamarr.server.repositories.notification;

import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.ShareSides;
import com.streamarr.server.domain.notification.UnreadNews;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The writes of the guarded Notifications (ADR 0063), kept until their table is removed. */
public interface ShareNotificationRepositoryCustom {

  /** The requests about the share. */
  List<Notification> findRequestsAbout(UUID shareId);

  /**
   * Marks read at the instant those of the given Notifications that are the recipient's and still
   * unread, writing nothing else, so an update in place that commits first keeps what it stored.
   * Answers the ids of the ones it marked.
   */
  List<UUID> markRead(Collection<UUID> notificationIds, UUID recipientAccountId, Instant now);

  /** Stores a new Notification for its recipient, unread, and answers its id. */
  UUID insert(NotificationDraft<NotificationKind, ProfileShareFacts> draft);

  /**
   * The rule of one unread news per recipient, kind and share, in one statement: stores new unread
   * news, or moves the recipient's unread news of the kind about the share to the event's time when
   * that is later, and holds it until the transaction ends. Two events at the same moment update
   * one Notification. News it moves keeps the side it was addressed to.
   */
  UnreadNews<ProfileShareFacts> upsertUnreadNews(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft);

  /**
   * Stores the facts on a Notification that this transaction already holds, except the side it was
   * addressed to, which nothing changes after it is written.
   */
  void replaceFacts(UUID notificationId, ProfileShareFacts facts);

  /**
   * The share as it is stored now, with its Profile's current name and the Accounts on each of its
   * sides, read past Hibernate's first-level cache; empty when the share no longer exists.
   */
  Optional<ShareSides> findShareSides(UUID shareId);
}
