package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.jooq.generated.Tables;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ContinueWatchingRepositoryImpl implements ContinueWatchingRepository {

  private final DSLContext dsl;

  @Override
  public List<UUID> findCollectableIds(UUID userId, int limit) {
    var isWatched =
        exists(
            select(Tables.WATCH_HISTORY.ID)
                .from(Tables.WATCH_HISTORY)
                .where(
                    Tables.WATCH_HISTORY
                        .COLLECTABLE_ID
                        .eq(Tables.MEDIA_FILE.MEDIA_ID)
                        .and(Tables.WATCH_HISTORY.USER_ID.eq(userId))
                        .and(Tables.WATCH_HISTORY.DISMISSED_AT.isNull())));

    return dsl.select(
            Tables.MEDIA_FILE.MEDIA_ID, max(SESSION_PROGRESS.LAST_MODIFIED_ON).as("last_activity"))
        .from(SESSION_PROGRESS)
        .innerJoin(Tables.MEDIA_FILE)
        .on(SESSION_PROGRESS.MEDIA_FILE_ID.eq(Tables.MEDIA_FILE.ID))
        .where(
            SESSION_PROGRESS
                .USER_ID
                .eq(userId)
                .and(SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))
                .and(not(isWatched)))
        .groupBy(Tables.MEDIA_FILE.MEDIA_ID)
        .orderBy(max(SESSION_PROGRESS.LAST_MODIFIED_ON).sort(SortOrder.DESC))
        .limit(limit)
        .fetch(Tables.MEDIA_FILE.MEDIA_ID);
  }
}
