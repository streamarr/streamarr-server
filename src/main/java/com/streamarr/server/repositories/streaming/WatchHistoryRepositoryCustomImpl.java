package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
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
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    var insert =
        dsl.insertInto(
                WATCH_HISTORY,
                WATCH_HISTORY.USER_ID,
                WATCH_HISTORY.COLLECTABLE_ID,
                WATCH_HISTORY.WATCHED_AT,
                WATCH_HISTORY.DURATION_SECONDS,
                WATCH_HISTORY.CREATED_ON,
                WATCH_HISTORY.CREATED_BY,
                WATCH_HISTORY.LAST_MODIFIED_ON,
                WATCH_HISTORY.LAST_MODIFIED_BY)
            .values((UUID) null, null, null, null, null, null, null, null)
            .onConflict(
                WATCH_HISTORY.USER_ID, WATCH_HISTORY.COLLECTABLE_ID, WATCH_HISTORY.WATCHED_AT)
            .doNothing();

    var batch = dsl.batch(insert);
    for (var collectableId : collectableIds) {
      batch =
          batch.bind(
              userId, collectableId, watchedAtOdt, durationSeconds, now, auditUser, now, auditUser);
    }
    batch.execute();
  }

  @Override
  public void dismissAll(UUID userId, Collection<UUID> collectableIds) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    dsl.update(WATCH_HISTORY)
        .set(WATCH_HISTORY.DISMISSED_AT, now)
        .set(WATCH_HISTORY.LAST_MODIFIED_ON, now)
        .set(WATCH_HISTORY.LAST_MODIFIED_BY, auditUser)
        .where(WATCH_HISTORY.USER_ID.eq(userId))
        .and(WATCH_HISTORY.COLLECTABLE_ID.in(collectableIds))
        .and(WATCH_HISTORY.DISMISSED_AT.isNull())
        .execute();
  }
}
