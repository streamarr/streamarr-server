package com.streamarr.server.fakes;

import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.ShareSides;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortValue;
import com.streamarr.server.repositories.notification.NotificationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Notifications in memory for the list, the unread count and the retention sweep. It knows no share
 * and no Account, so it holds news alone, reads every Notification of a recipient as one a read
 * rule could show, which leaves the guard to decide each, and treats no recipient as disabled; the
 * reads and writes that need the share are left to the database.
 */
public class FakeNotificationRepository extends FakeJpaRepository<Notification>
    implements NotificationRepository {

  @Override
  public <S extends Notification> S save(S notification) {
    if (notification.getForm() != NotificationForm.NEWS) {
      throw new NotImplementedException("The fake knows no share, so it holds no request.");
    }

    return super.save(notification);
  }

  @Override
  public int deleteHistoryBefore(Instant cutoff, Instant now) {
    var history =
        database.values().stream()
            .filter(notification -> notification.getReadAt().isPresent())
            .filter(notification -> notification.getLatestEventAt().isBefore(cutoff))
            .map(Notification::getId)
            .toList();
    history.forEach(database::remove);
    return history.size();
  }

  @Override
  public List<Notification> findUnreadNewsBefore(Instant cutoff, Optional<UUID> after, int limit) {
    return database.values().stream()
        .filter(notification -> unreadNewsBefore(notification, cutoff))
        .filter(
            notification -> after.map(id -> notification.getId().compareTo(id) > 0).orElse(true))
        .sorted(Comparator.comparing(Notification::getId))
        .limit(limit)
        .toList();
  }

  @Override
  public int deleteUnreadNewsBefore(Collection<UUID> notificationIds, Instant cutoff) {
    var deleted =
        notificationIds.stream()
            .filter(
                id ->
                    findById(id)
                        .filter(notification -> unreadNewsBefore(notification, cutoff))
                        .isPresent())
            .toList();
    deleted.forEach(database::remove);
    return deleted.size();
  }

  private static boolean unreadNewsBefore(Notification notification, Instant cutoff) {
    return notification.getReadAt().isEmpty() && notification.getLatestEventAt().isBefore(cutoff);
  }

  @Override
  public List<Row<Notification>> rowsOfRecipient(UUID recipientAccountId, Seek seek) {
    return InMemoryKeyset.rowsBeyond(
        ofRecipient(recipientAccountId)
            .map(
                notification ->
                    new Row<>(
                        notification,
                        new Position(
                            new SortValue.OfInstant(notification.getLatestEventAt()),
                            notification.getId())))
            .toList(),
        seek);
  }

  @Override
  public List<Notification> findUnreadOfRecipient(UUID recipientAccountId, Instant now) {
    return ofRecipient(recipientAccountId)
        .filter(notification -> notification.getReadAt().isEmpty())
        .toList();
  }

  private Stream<Notification> ofRecipient(UUID recipientAccountId) {
    return database.values().stream()
        .filter(notification -> notification.getRecipientAccountId().equals(recipientAccountId));
  }

  @Override
  public List<Row<Notification>> rowsOfRecipientWithIds(
      UUID recipientAccountId, Collection<UUID> notificationIds) {
    throw new NotImplementedException();
  }

  @Override
  public List<GuardedNotification> findRequestsAbout(UUID shareId) {
    throw new NotImplementedException();
  }

  @Override
  public List<Notification> findUnreadOfRecipientWithIds(
      UUID recipientAccountId, Collection<UUID> notificationIds, Instant now) {
    throw new NotImplementedException();
  }

  @Override
  public List<UUID> markRead(
      Collection<UUID> notificationIds, UUID recipientAccountId, Instant now) {
    throw new NotImplementedException();
  }

  @Override
  public UUID insert(NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    throw new NotImplementedException();
  }

  @Override
  public UnreadNews<ProfileShareFacts> upsertUnreadNews(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    throw new NotImplementedException();
  }

  @Override
  public void replaceFacts(UUID notificationId, ProfileShareFacts facts) {
    throw new NotImplementedException();
  }

  @Override
  public Optional<ShareSides> findShareSides(UUID shareId) {
    throw new NotImplementedException();
  }
}
