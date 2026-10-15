package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.repositories.LastWatchedExpression;
import com.streamarr.server.repositories.WatchStateConditions;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ContinueWatchingRepositoryImpl implements ContinueWatchingRepository {

  private final DSLContext dsl;

  @Override
  public List<UUID> findCollectableIds(UUID profileId, int limit) {
    var itemId = MEDIA_FILE.MEDIA_ID;
    var lastWatchedAt =
        LastWatchedExpression.lastWatchedAt(profileId, CollectableScope.MOVIE_OR_EPISODE, itemId);

    return dsl.select(itemId)
        .from(SESSION_PROGRESS)
        .innerJoin(MEDIA_FILE)
        .on(SESSION_PROGRESS.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .where(
            SESSION_PROGRESS
                .PROFILE_ID
                .eq(profileId)
                .and(SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))
                .and(WatchStateConditions.hasResumePosition(profileId, itemId)))
        .groupBy(itemId)
        .orderBy(lastWatchedAt.desc(), itemId.desc())
        .limit(limit)
        .fetch(itemId);
  }
}
