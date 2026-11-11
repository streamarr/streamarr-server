package com.streamarr.server.repositories.notification;

import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.UnreadNews;
import java.util.UUID;

/** The writes of Notifications about a Profile share (ADR 0063). */
public interface NotificationRepositoryCustom {

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
}
