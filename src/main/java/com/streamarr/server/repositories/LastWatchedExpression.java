package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.jooq.generated.tables.Episode;
import com.streamarr.server.jooq.generated.tables.MediaFile;
import com.streamarr.server.jooq.generated.tables.Season;
import com.streamarr.server.jooq.generated.tables.SessionProgress;
import com.streamarr.server.jooq.generated.tables.WatchHistory;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;

/**
 * The one last-watched expression. The {@code LAST_WATCHED} sort and its cursor values both read
 * this expression, so a cursor always matches the sort that produced the cursor.
 *
 * <p>An item was last watched at the later of two times: its latest completion or manual mark that
 * has not been dismissed, and its latest session progress with a position. Progress older than that
 * completion does not count, and the later of the two times already leaves such progress out.
 */
public final class LastWatchedExpression {

  private static final WatchHistory COMPLETION = WATCH_HISTORY.as("last_completion");
  private static final SessionProgress PROGRESS = SESSION_PROGRESS.as("last_progress");
  private static final MediaFile PROGRESS_FILE = MEDIA_FILE.as("last_progress_file");
  private static final Episode EPISODE = Episode.EPISODE.as("last_watched_episode");
  private static final Season SEASON = Season.SEASON.as("last_watched_season");

  private LastWatchedExpression() {}

  public static Field<OffsetDateTime> lastWatchedAt(
      UUID profileId, CollectableScope scope, Field<UUID> itemId) {
    return switch (scope) {
      case DIRECT_MEDIA ->
          greatest(
              latestCompletionAt(profileId, COMPLETION.COLLECTABLE_ID.eq(itemId)),
              latestProgressAt(profileId, PROGRESS_FILE.MEDIA_ID.eq(itemId)));
      case SEASON -> forEpisodesWhere(profileId, EPISODE.SEASON_ID.eq(itemId));
      case SERIES -> forEpisodesWhere(profileId, SEASON.SERIES_ID.eq(itemId));
    };
  }

  private static Field<OffsetDateTime> forEpisodesWhere(UUID profileId, Condition episodes) {
    var episodeIds =
        select(EPISODE.ID)
            .from(EPISODE)
            .innerJoin(SEASON)
            .on(EPISODE.SEASON_ID.eq(SEASON.ID))
            .where(episodes);
    return greatest(
        latestCompletionAt(profileId, COMPLETION.COLLECTABLE_ID.in(episodeIds)),
        latestProgressAt(profileId, PROGRESS_FILE.MEDIA_ID.in(episodeIds)));
  }

  private static Field<OffsetDateTime> latestCompletionAt(UUID profileId, Condition items) {
    return select(max(COMPLETION.WATCHED_AT))
        .from(COMPLETION)
        .where(items)
        .and(COMPLETION.PROFILE_ID.eq(profileId))
        .and(COMPLETION.DISMISSED_AT.isNull())
        .asField();
  }

  private static Field<OffsetDateTime> latestProgressAt(UUID profileId, Condition items) {
    return select(max(PROGRESS.LAST_MODIFIED_ON))
        .from(PROGRESS)
        .innerJoin(PROGRESS_FILE)
        .on(PROGRESS.MEDIA_FILE_ID.eq(PROGRESS_FILE.ID))
        .where(items)
        .and(PROGRESS.PROFILE_ID.eq(profileId))
        .and(PROGRESS.POSITION_SECONDS.greaterThan(0))
        .asField();
  }
}
