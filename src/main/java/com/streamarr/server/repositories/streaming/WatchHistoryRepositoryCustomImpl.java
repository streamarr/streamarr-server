package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.repositories.LastWatchedExpression;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record2;

@RequiredArgsConstructor
public class WatchHistoryRepositoryCustomImpl implements WatchHistoryRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public void batchInsert(
      UUID profileId, Collection<UUID> collectableIds, Instant watchedAt, int durationSeconds) {
    var watchedAtOdt = watchedAt.atOffset(ZoneOffset.UTC);
    var insert =
        dsl.insertInto(
                WATCH_HISTORY,
                WATCH_HISTORY.PROFILE_ID,
                WATCH_HISTORY.COLLECTABLE_ID,
                WATCH_HISTORY.WATCHED_AT,
                WATCH_HISTORY.DURATION_SECONDS)
            .values((UUID) null, null, null, null)
            .onConflict(
                WATCH_HISTORY.PROFILE_ID, WATCH_HISTORY.COLLECTABLE_ID, WATCH_HISTORY.WATCHED_AT)
            .doNothing();

    var batch = dsl.batch(insert);
    for (var collectableId : collectableIds) {
      batch = batch.bind(profileId, collectableId, watchedAtOdt, durationSeconds);
    }
    batch.execute();
  }

  @Override
  public void dismissAll(UUID profileId, Collection<UUID> collectableIds) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    dsl.update(WATCH_HISTORY)
        .set(WATCH_HISTORY.DISMISSED_AT, now)
        .where(WATCH_HISTORY.PROFILE_ID.eq(profileId))
        .and(WATCH_HISTORY.COLLECTABLE_ID.in(collectableIds))
        .and(WATCH_HISTORY.DISMISSED_AT.isNull())
        .execute();
  }

  @Override
  public void reassignProfile(UUID fromProfileId, UUID toProfileId) {
    dsl.update(WATCH_HISTORY)
        .set(WATCH_HISTORY.PROFILE_ID, toProfileId)
        .where(WATCH_HISTORY.PROFILE_ID.eq(fromProfileId))
        .execute();
  }

  @Override
  public Map<UUID, Instant> findLastWatchedByItemIds(
      UUID profileId, CollectableScope scope, Collection<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return Map.of();
    }

    var lastWatchedAt = LastWatchedExpression.lastWatchedAt(profileId, scope, BASE_COLLECTABLE.ID);
    return dsl
        .select(BASE_COLLECTABLE.ID, lastWatchedAt)
        .from(BASE_COLLECTABLE)
        .where(BASE_COLLECTABLE.ID.in(itemIds))
        .fetch()
        .stream()
        .filter(row -> row.value2() != null)
        .collect(Collectors.toMap(Record2::value1, row -> row.value2().toInstant()));
  }
}
