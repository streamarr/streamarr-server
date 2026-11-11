package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.services.notification.events.NotificationsWrittenEvent;
import com.streamarr.server.services.notification.events.WrittenNotification;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tells an event to its recipients under the rules every kind follows (ADR 0063): one copy for each
 * recipient Account, none for the Account that acted, a new Notification for each request, and at
 * most one unread news of a kind about one subject for each recipient, which a later event of the
 * kind updates in place. It runs in the transaction of the change that caused the event, and
 * announces each Notification it made or updated as one {@link NotificationsWrittenEvent}.
 *
 * @param <K> the kinds
 * @param <F> what the kinds carry beyond their subject
 */
@RequiredArgsConstructor
public final class NotificationWriter<K, F> {

  private final NotificationKinds<K, F> kinds;
  private final NotificationStore<K, F> store;
  private final ApplicationEventPublisher events;

  /** Tells the event and answers each Notification it made or updated in place. */
  public List<WrittenNotification> tell(NotificationEvent<K, F> event) {
    var written =
        event.recipients().stream()
            .filter(recipient -> event.actingAccountId().filter(recipient::equals).isEmpty())
            .map(recipient -> write(event, recipient))
            .toList();
    if (!written.isEmpty()) {
      events.publishEvent(new NotificationsWrittenEvent(written));
    }

    return written;
  }

  private WrittenNotification write(NotificationEvent<K, F> event, UUID recipient) {
    var draft =
        NotificationDraft.<K, F>builder()
            .recipientAccountId(recipient)
            .kind(event.kind())
            .subjectId(event.subjectId())
            .facts(event.facts())
            .at(event.at())
            .build();
    return switch (kinds.formOf(event.kind())) {
      case REQUEST ->
          new WrittenNotification(store.make(draft), recipient, WrittenNotification.Write.MADE);
      case NEWS -> news(draft);
    };
  }

  private WrittenNotification news(NotificationDraft<K, F> draft) {
    return switch (store.makeOrMoveUnreadNews(draft)) {
      case UnreadNews.Made<F>(var notificationId) ->
          new WrittenNotification(
              notificationId, draft.recipientAccountId(), WrittenNotification.Write.MADE);
      case UnreadNews.Moved<F>(var notificationId, var stored) -> {
        var combined = kinds.combine(draft.kind(), stored, draft.facts());
        if (!combined.equals(stored)) {
          store.replaceFacts(notificationId, combined);
        }

        yield new WrittenNotification(
            notificationId, draft.recipientAccountId(), WrittenNotification.Write.UPDATED);
      }
    };
  }
}
