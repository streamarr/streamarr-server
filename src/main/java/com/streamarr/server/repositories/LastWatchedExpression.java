package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;
import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.Season.SEASON;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.streaming.CollectableScope;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;

/**
 * The one last-watched expression. The {@code LAST_WATCHED} sort and its cursor values both read
 * it, so a cursor always matches the sort that produced it.
 */
public final class LastWatchedExpression {

  private LastWatchedExpression() {}

  public static Field<OffsetDateTime> lastWatchedAt(
      UUID profileId, CollectableScope scope, Field<UUID> itemId) {
    return switch (scope) {
      case DIRECT_MEDIA -> forDirectMedia(profileId, itemId);
      case SEASON -> forEpisodesWhere(profileId, EPISODE.SEASON_ID.eq(itemId));
      case SERIES -> forEpisodesWhere(profileId, SEASON.SERIES_ID.eq(itemId));
    };
  }

  private static Field<OffsetDateTime> forDirectMedia(UUID profileId, Field<UUID> itemId) {
    return select(max(SESSION_PROGRESS.LAST_MODIFIED_ON))
        .from(SESSION_PROGRESS)
        .innerJoin(MEDIA_FILE)
        .on(SESSION_PROGRESS.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .where(MEDIA_FILE.MEDIA_ID.eq(itemId).and(SESSION_PROGRESS.PROFILE_ID.eq(profileId)))
        .asField();
  }

  private static Field<OffsetDateTime> forEpisodesWhere(UUID profileId, Condition episodes) {
    return select(max(SESSION_PROGRESS.LAST_MODIFIED_ON))
        .from(SESSION_PROGRESS)
        .innerJoin(MEDIA_FILE)
        .on(SESSION_PROGRESS.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .innerJoin(EPISODE)
        .on(MEDIA_FILE.MEDIA_ID.eq(EPISODE.ID))
        .innerJoin(SEASON)
        .on(EPISODE.SEASON_ID.eq(SEASON.ID))
        .where(episodes.and(SESSION_PROGRESS.PROFILE_ID.eq(profileId)))
        .asField();
  }
}
