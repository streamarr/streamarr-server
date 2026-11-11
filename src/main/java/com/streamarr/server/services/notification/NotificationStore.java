package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.UnreadNews;
import java.util.UUID;

/**
 * Where the Notifications of one family of kinds are kept, inside the transaction of the change
 * that caused them.
 *
 * @param <K> the kinds
 * @param <F> what the kinds carry beyond their subject
 */
public interface NotificationStore<K, F> {

  /** Stores a new unread Notification and answers its id. */
  UUID make(NotificationDraft<K, F> draft);

  /**
   * Stores new unread news, or moves the recipient's unread news of the kind about the subject to
   * the draft's time and holds it until the transaction ends, so that its facts can be combined.
   */
  UnreadNews<F> makeOrMoveUnreadNews(NotificationDraft<K, F> draft);

  /** Stores the facts on a Notification this transaction already holds. */
  void replaceFacts(UUID notificationId, F facts);
}
