package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.selectOne;

import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.jooq.generated.tables.MediaFile;
import com.streamarr.server.jooq.generated.tables.SessionProgress;
import com.streamarr.server.jooq.generated.tables.WatchHistory;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;

/**
 * The watch-state conditions for one movie or episode. The watch status filter and Continue
 * Watching both build on these conditions, so the two lists apply the same rule as the {@code
 * watchStatus} field.
 *
 * <p>The subqueries use their own table aliases. A caller can therefore correlate the subqueries
 * with a query that reads the same tables.
 */
public final class WatchStateConditions {

  private static final WatchHistory COMPLETION = WATCH_HISTORY.as("completion");
  private static final SessionProgress PROGRESS = SESSION_PROGRESS.as("resume_progress");
  private static final MediaFile PROGRESS_FILE = MEDIA_FILE.as("resume_progress_file");

  private WatchStateConditions() {}

  public static Condition watchStatusIs(UUID profileId, Field<UUID> itemId, WatchStatus status) {
    return switch (status) {
      case WATCHED ->
          hasCompletion(profileId, itemId).and(not(hasResumePosition(profileId, itemId)));
      case IN_PROGRESS -> hasResumePosition(profileId, itemId);
      case UNWATCHED ->
          not(hasCompletion(profileId, itemId)).and(not(hasResumePosition(profileId, itemId)));
    };
  }

  public static Condition hasCompletion(UUID profileId, Field<UUID> itemId) {
    return exists(selectOne().from(COMPLETION).where(isCompletionOf(profileId, itemId)));
  }

  /**
   * True when the item has session progress with a position that the database stamped after the
   * item's latest completion that has not been dismissed.
   */
  public static Condition hasResumePosition(UUID profileId, Field<UUID> itemId) {
    return exists(
        selectOne()
            .from(PROGRESS)
            .innerJoin(PROGRESS_FILE)
            .on(PROGRESS.MEDIA_FILE_ID.eq(PROGRESS_FILE.ID))
            .where(
                PROGRESS_FILE
                    .MEDIA_ID
                    .eq(itemId)
                    .and(PROGRESS.PROFILE_ID.eq(profileId))
                    .and(PROGRESS.POSITION_SECONDS.greaterThan(0))
                    .and(not(hasCompletionSinceProgress(profileId, itemId)))));
  }

  private static Condition hasCompletionSinceProgress(UUID profileId, Field<UUID> itemId) {
    return exists(
        selectOne()
            .from(COMPLETION)
            .where(
                isCompletionOf(profileId, itemId)
                    .and(COMPLETION.WATCHED_AT.greaterOrEqual(PROGRESS.LAST_MODIFIED_ON))));
  }

  private static Condition isCompletionOf(UUID profileId, Field<UUID> itemId) {
    return COMPLETION
        .COLLECTABLE_ID
        .eq(itemId)
        .and(COMPLETION.PROFILE_ID.eq(profileId))
        .and(COMPLETION.DISMISSED_AT.isNull());
  }
}
