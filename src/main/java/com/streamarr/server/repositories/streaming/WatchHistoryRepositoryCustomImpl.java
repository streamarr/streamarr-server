package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class WatchHistoryRepositoryCustomImpl implements WatchHistoryRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public void batchInsert(
      UUID userId, Collection<UUID> collectableIds, Instant watchedAt, int durationSeconds) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var watchedAtOdt = watchedAt.atOffset(ZoneOffset.UTC);

    var queries =
        collectableIds.stream()
            .map(
                collectableId ->
                    (Query)
                        dsl.insertInto(WATCH_HISTORY)
                            .set(WATCH_HISTORY.USER_ID, userId)
                            .set(WATCH_HISTORY.COLLECTABLE_ID, collectableId)
                            .set(WATCH_HISTORY.WATCHED_AT, watchedAtOdt)
                            .set(WATCH_HISTORY.DURATION_SECONDS, durationSeconds)
                            .set(WATCH_HISTORY.CREATED_BY, auditUser)
                            .set(WATCH_HISTORY.LAST_MODIFIED_BY, auditUser))
            .toArray(Query[]::new);

    dsl.batch(queries).execute();
  }

  @Override
  public void dismissAll(UUID userId, Collection<UUID> collectableIds) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    dsl.update(WATCH_HISTORY)
        .set(WATCH_HISTORY.DISMISSED_AT, now)
        .where(WATCH_HISTORY.USER_ID.eq(userId))
        .and(WATCH_HISTORY.COLLECTABLE_ID.in(collectableIds))
        .and(WATCH_HISTORY.DISMISSED_AT.isNull())
        .execute();
  }
}
