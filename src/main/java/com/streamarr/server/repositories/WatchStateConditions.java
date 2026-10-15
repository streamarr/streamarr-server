package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;

/**
 * The watch-state conditions for one movie or episode. The watch status filter and Continue
 * Watching both build on them, so the two lists apply the same rule.
 */
public final class WatchStateConditions {

  private WatchStateConditions() {}

  public static Condition hasCompletion(UUID profileId, Field<UUID> itemId) {
    return exists(
        selectOne()
            .from(WATCH_HISTORY)
            .where(
                WATCH_HISTORY
                    .COLLECTABLE_ID
                    .eq(itemId)
                    .and(WATCH_HISTORY.PROFILE_ID.eq(profileId))
                    .and(WATCH_HISTORY.DISMISSED_AT.isNull())));
  }

  public static Condition hasProgress(UUID profileId, Field<UUID> itemId) {
    return exists(
        selectOne()
            .from(SESSION_PROGRESS)
            .innerJoin(MEDIA_FILE)
            .on(SESSION_PROGRESS.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
            .where(
                MEDIA_FILE
                    .MEDIA_ID
                    .eq(itemId)
                    .and(SESSION_PROGRESS.PROFILE_ID.eq(profileId))
                    .and(SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))));
  }
}
