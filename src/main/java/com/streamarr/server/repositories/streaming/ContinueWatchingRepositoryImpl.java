package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;

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
  public List<UUID> findCollectableIds(UUID profileId, int limit) {
    var lastActivity = max(SESSION_PROGRESS.LAST_MODIFIED_ON);

    var isWatched =
        exists(
            select(WATCH_HISTORY.ID)
                .from(WATCH_HISTORY)
                .where(
                    WATCH_HISTORY
                        .COLLECTABLE_ID
                        .eq(MEDIA_FILE.MEDIA_ID)
                        .and(WATCH_HISTORY.PROFILE_ID.eq(profileId))
                        .and(WATCH_HISTORY.DISMISSED_AT.isNull())));

    return dsl.select(MEDIA_FILE.MEDIA_ID, lastActivity)
        .from(SESSION_PROGRESS)
        .innerJoin(MEDIA_FILE)
        .on(SESSION_PROGRESS.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .where(
            SESSION_PROGRESS
                .PROFILE_ID
                .eq(profileId)
                .and(SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))
                .and(not(isWatched)))
        .groupBy(MEDIA_FILE.MEDIA_ID)
        .orderBy(lastActivity.sort(SortOrder.DESC))
        .limit(limit)
        .fetch(MEDIA_FILE.MEDIA_ID);
  }
}
