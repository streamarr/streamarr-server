package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.repositories.notification.ShareNotificationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** The sharing kinds' Notifications, kept in PostgreSQL. */
@RequiredArgsConstructor
class ProfileShareNotificationStore
    implements NotificationStore<NotificationKind, ProfileShareFacts> {

  private final ShareNotificationRepository notificationRepository;

  @Override
  public UUID make(NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    return notificationRepository.insert(draft);
  }

  @Override
  public UnreadNews<ProfileShareFacts> makeOrMoveUnreadNews(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    return notificationRepository.upsertUnreadNews(draft);
  }

  @Override
  public void replaceFacts(UUID notificationId, ProfileShareFacts facts) {
    notificationRepository.replaceFacts(notificationId, facts);
  }
}
