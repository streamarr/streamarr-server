package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.not;

import com.streamarr.server.repositories.WatchStateConditions;
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

    var isWatched = WatchStateConditions.hasCompletion(profileId, MEDIA_FILE.MEDIA_ID);

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
