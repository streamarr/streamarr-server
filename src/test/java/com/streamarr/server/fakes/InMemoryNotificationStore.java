package com.streamarr.server.fakes;

import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.services.notification.NotificationStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.Builder;
import lombok.NonNull;

/**
 * Keeps Notifications of any kind in memory, under the same rule as the database: at most one
 * unread news of a kind about one subject for each recipient.
 */
public class InMemoryNotificationStore<K, F> implements NotificationStore<K, F> {

  private final Function<K, NotificationForm> formOf;
  private final Map<UUID, Stored<K, F>> rows = new LinkedHashMap<>();

  public InMemoryNotificationStore(Function<K, NotificationForm> formOf) {
    this.formOf = formOf;
  }

  @Override
  public UUID make(NotificationDraft<K, F> draft) {
    var id = UUID.randomUUID();
    rows.put(
        id,
        Stored.<K, F>builder()
            .id(id)
            .recipientAccountId(draft.recipientAccountId())
            .kind(draft.kind())
            .subjectId(draft.subjectId())
            .facts(draft.facts())
            .createdAt(draft.at())
            .latestEventAt(draft.at())
            .readAt(Optional.empty())
            .build());
    return id;
  }

  @Override
  public UnreadNews<F> makeOrMoveUnreadNews(NotificationDraft<K, F> draft) {
    var unread =
        rows.values().stream()
            .filter(row -> formOf.apply(row.kind()) == NotificationForm.NEWS)
            .filter(row -> row.readAt().isEmpty())
            .filter(row -> row.recipientAccountId().equals(draft.recipientAccountId()))
            .filter(row -> row.kind().equals(draft.kind()))
            .filter(row -> row.subjectId().equals(draft.subjectId()))
            .findFirst();
    if (unread.isEmpty()) {
      return new UnreadNews.Made<>(make(draft));
    }

    var stored = unread.get();
    var latest = stored.latestEventAt().isAfter(draft.at()) ? stored.latestEventAt() : draft.at();
    rows.put(stored.id(), stored.toBuilder().latestEventAt(latest).build());
    return new UnreadNews.Moved<>(stored.id(), stored.facts());
  }

  @Override
  public void replaceFacts(UUID notificationId, F facts) {
    rows.computeIfPresent(notificationId, (_, row) -> row.toBuilder().facts(facts).build());
  }

  public void markRead(UUID notificationId, Instant at) {
    rows.computeIfPresent(
        notificationId, (_, row) -> row.toBuilder().readAt(Optional.of(at)).build());
  }

  public List<Stored<K, F>> rows() {
    return List.copyOf(rows.values());
  }

  public List<Stored<K, F>> rowsOf(UUID recipientAccountId) {
    return rows.values().stream()
        .filter(row -> row.recipientAccountId().equals(recipientAccountId))
        .toList();
  }

  /** One stored Notification. */
  @Builder(toBuilder = true)
  public record Stored<K, F>(
      @NonNull UUID id,
      @NonNull UUID recipientAccountId,
      @NonNull K kind,
      @NonNull UUID subjectId,
      @NonNull F facts,
      @NonNull Instant createdAt,
      @NonNull Instant latestEventAt,
      @NonNull Optional<Instant> readAt) {}
}
