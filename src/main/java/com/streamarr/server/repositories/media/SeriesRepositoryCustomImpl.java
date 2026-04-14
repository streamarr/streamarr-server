package com.streamarr.server.repositories.media;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

@RequiredArgsConstructor
public class SeriesRepositoryCustomImpl implements SeriesRepositoryCustom {

  private final DSLContext context;
  @PersistenceContext private final EntityManager entityManager;

  public List<Series> seekWithFilter(MediaPaginationOptions options) {

    var shouldReverse =
        options.getPaginationOptions().getPaginationDirection().equals(PaginationDirection.REVERSE);
    var filter = options.getMediaFilter();
    var originalDirection = filter.getSortDirection();

    if (shouldReverse) {
      filter = JooqQueryHelper.reverseFilter(filter);
    }

    var orderByColumns =
        new SortField[] {
          buildOrderBy(filter), Tables.BASE_COLLECTABLE.ID.sort(filter.getSortDirection())
        };

    var seekCondition =
        JooqQueryHelper.buildSeekCondition(
            filter, sortField(filter), orderByColumns, options.getCursorId().orElseThrow());

    var query =
        context
            .select()
            .from(Tables.SERIES)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.SERIES.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .where(seekCondition)
            .and(JooqQueryHelper.libraryCondition(filter.getLibraryId()))
            .and(
                JooqQueryHelper.startLetterCondition(
                    filter.getStartLetter(), originalDirection, filter.getSortBy()))
            .and(filterConditions(filter))
            .orderBy(orderByColumns)
            // N+2 (Allows us to efficiently check if there are items before AND after N)
            .limit(options.getPaginationOptions().getLimit() + 2);

    var results = JooqQueryHelper.nativeQuery(entityManager, query, Series.class);

    if (shouldReverse) {
      Collections.reverse(results);
    }

    return results;
  }

  public Optional<Series> findByTmdbId(String tmdbId) {
    var query =
        context
            .select(Tables.SERIES.asterisk(), Tables.BASE_COLLECTABLE.asterisk())
            .from(Tables.SERIES)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.SERIES.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .join(Tables.EXTERNAL_IDENTIFIER)
            .on(Tables.EXTERNAL_IDENTIFIER.ENTITY_ID.eq(Tables.SERIES.ID))
            .where(
                Tables.EXTERNAL_IDENTIFIER
                    .EXTERNAL_SOURCE_TYPE
                    .eq(inline(ExternalSourceType.TMDB))
                    .and(Tables.EXTERNAL_IDENTIFIER.EXTERNAL_ID.eq(tmdbId)))
            .limit(1);

    List<Series> results = JooqQueryHelper.nativeQuery(entityManager, query, Series.class);

    if (results.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(results.getFirst());
  }

  public List<Series> findFirstWithFilter(MediaPaginationOptions options) {

    var orderByColumns =
        new SortField[] {
          buildOrderBy(options.getMediaFilter()),
          Tables.BASE_COLLECTABLE.ID.sort(options.getMediaFilter().getSortDirection())
        };

    var query =
        context
            .select()
            .from(Tables.SERIES)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.SERIES.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .where(JooqQueryHelper.libraryCondition(options.getMediaFilter().getLibraryId()))
            .and(
                JooqQueryHelper.startLetterCondition(
                    options.getMediaFilter().getStartLetter(),
                    options.getMediaFilter().getSortDirection(),
                    options.getMediaFilter().getSortBy()))
            .and(filterConditions(options.getMediaFilter()))
            .orderBy(orderByColumns)
            .limit(options.getPaginationOptions().getLimit() + 1);

    return JooqQueryHelper.nativeQuery(entityManager, query, Series.class);
  }

  private Condition filterConditions(MediaFilter filter) {
    var condition = noCondition();

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.SERIES.ID,
                Tables.SERIES_GENRE,
                Tables.SERIES_GENRE.SERIES_ID,
                Tables.SERIES_GENRE.GENRE_ID,
                filter.getGenreIds()));

    condition =
        condition.and(
            JooqQueryHelper.yearCondition(Tables.SERIES.FIRST_AIR_DATE, filter.getYears()));

    condition =
        condition.and(
            JooqQueryHelper.contentRatingCondition(
                Tables.SERIES.CONTENT_RATING_VALUE, filter.getContentRatings()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.SERIES.ID,
                Tables.SERIES_COMPANY,
                Tables.SERIES_COMPANY.SERIES_ID,
                Tables.SERIES_COMPANY.COMPANY_ID,
                filter.getStudioIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.SERIES.ID,
                Tables.SERIES_DIRECTOR,
                Tables.SERIES_DIRECTOR.SERIES_ID,
                Tables.SERIES_DIRECTOR.PERSON_ID,
                filter.getDirectorIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.SERIES.ID,
                Tables.SERIES_PERSON,
                Tables.SERIES_PERSON.SERIES_ID,
                Tables.SERIES_PERSON.PERSON_ID,
                filter.getCastMemberIds()));

    condition = condition.and(JooqQueryHelper.unmatchedCondition(filter.getUnmatched()));
    condition = condition.and(watchStatusCondition(filter.getWatchStatus()));

    return condition;
  }

  private Condition watchStatusCondition(WatchStatus watchStatus) {
    if (watchStatus == null) {
      return noCondition();
    }

    // TODO(#163): Replace with authenticated user ID from Spring Security
    var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Subquery: series has at least one episode
    var hasEpisodes =
        exists(
            select(Tables.EPISODE.ID)
                .from(Tables.EPISODE)
                .innerJoin(Tables.SEASON)
                .on(Tables.EPISODE.SEASON_ID.eq(Tables.SEASON.ID))
                .where(Tables.SEASON.SERIES_ID.eq(Tables.BASE_COLLECTABLE.ID)));

    // Subquery: all episodes are watched (no unwatched episode exists)
    var allEpisodesWatched =
        not(
            exists(
                select(Tables.EPISODE.ID)
                    .from(Tables.EPISODE)
                    .innerJoin(Tables.SEASON)
                    .on(Tables.EPISODE.SEASON_ID.eq(Tables.SEASON.ID))
                    .where(
                        Tables.SEASON
                            .SERIES_ID
                            .eq(Tables.BASE_COLLECTABLE.ID)
                            .and(
                                not(
                                    exists(
                                        select(Tables.WATCH_HISTORY.ID)
                                            .from(Tables.WATCH_HISTORY)
                                            .where(
                                                Tables.WATCH_HISTORY
                                                    .COLLECTABLE_ID
                                                    .eq(Tables.EPISODE.ID)
                                                    .and(Tables.WATCH_HISTORY.USER_ID.eq(userId))
                                                    .and(
                                                        Tables.WATCH_HISTORY.DISMISSED_AT
                                                            .isNull()))))))));

    // Subquery: any episode is watched
    var anyEpisodeWatched =
        exists(
            select(Tables.WATCH_HISTORY.ID)
                .from(Tables.WATCH_HISTORY)
                .innerJoin(Tables.EPISODE)
                .on(Tables.WATCH_HISTORY.COLLECTABLE_ID.eq(Tables.EPISODE.ID))
                .innerJoin(Tables.SEASON)
                .on(Tables.EPISODE.SEASON_ID.eq(Tables.SEASON.ID))
                .where(
                    Tables.SEASON
                        .SERIES_ID
                        .eq(Tables.BASE_COLLECTABLE.ID)
                        .and(Tables.WATCH_HISTORY.USER_ID.eq(userId))
                        .and(Tables.WATCH_HISTORY.DISMISSED_AT.isNull())));

    // Subquery: any episode has progress
    var anyEpisodeHasProgress =
        exists(
            select(Tables.SESSION_PROGRESS.ID)
                .from(Tables.SESSION_PROGRESS)
                .innerJoin(Tables.MEDIA_FILE)
                .on(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(Tables.MEDIA_FILE.ID))
                .innerJoin(Tables.EPISODE)
                .on(Tables.MEDIA_FILE.MEDIA_ID.eq(Tables.EPISODE.ID))
                .innerJoin(Tables.SEASON)
                .on(Tables.EPISODE.SEASON_ID.eq(Tables.SEASON.ID))
                .where(
                    Tables.SEASON
                        .SERIES_ID
                        .eq(Tables.BASE_COLLECTABLE.ID)
                        .and(Tables.SESSION_PROGRESS.USER_ID.eq(userId))
                        .and(Tables.SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))));

    var hasAnyWatchActivity = anyEpisodeWatched.or(anyEpisodeHasProgress);

    return switch (watchStatus) {
      case WATCHED -> hasEpisodes.and(allEpisodesWatched);
      case IN_PROGRESS -> hasAnyWatchActivity.and(not(hasEpisodes.and(allEpisodesWatched)));
      case UNWATCHED -> not(hasAnyWatchActivity);
    };
  }

  private Field<?> lastWatchedField() {
    // TODO(#163): Replace with authenticated user ID from Spring Security
    var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    return select(max(Tables.SESSION_PROGRESS.LAST_MODIFIED_ON))
        .from(Tables.SESSION_PROGRESS)
        .innerJoin(Tables.MEDIA_FILE)
        .on(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(Tables.MEDIA_FILE.ID))
        .innerJoin(Tables.EPISODE)
        .on(Tables.MEDIA_FILE.MEDIA_ID.eq(Tables.EPISODE.ID))
        .innerJoin(Tables.SEASON)
        .on(Tables.EPISODE.SEASON_ID.eq(Tables.SEASON.ID))
        .where(
            Tables.SEASON
                .SERIES_ID
                .eq(Tables.BASE_COLLECTABLE.ID)
                .and(Tables.SESSION_PROGRESS.USER_ID.eq(userId)))
        .asField();
  }

  @Override
  public Map<UUID, Instant> findLastWatchedBySeriesIds(Collection<UUID> seriesIds) {
    if (seriesIds == null || seriesIds.isEmpty()) {
      return Map.of();
    }

    // TODO(#163): Replace with authenticated user ID from Spring Security
    var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    var maxField = max(Tables.SESSION_PROGRESS.LAST_MODIFIED_ON);

    return context
        .select(Tables.SEASON.SERIES_ID, maxField)
        .from(Tables.SESSION_PROGRESS)
        .innerJoin(Tables.MEDIA_FILE)
        .on(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(Tables.MEDIA_FILE.ID))
        .innerJoin(Tables.EPISODE)
        .on(Tables.MEDIA_FILE.MEDIA_ID.eq(Tables.EPISODE.ID))
        .innerJoin(Tables.SEASON)
        .on(Tables.EPISODE.SEASON_ID.eq(Tables.SEASON.ID))
        .where(
            Tables.SEASON.SERIES_ID.in(seriesIds).and(Tables.SESSION_PROGRESS.USER_ID.eq(userId)))
        .groupBy(Tables.SEASON.SERIES_ID)
        .fetchMap(Tables.SEASON.SERIES_ID, r -> toInstant(r.get(maxField)));
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private Field<?> sortField(MediaFilter filter) {
    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT;
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON;
      case RELEASE_DATE -> Tables.SERIES.FIRST_AIR_DATE;
      case RUNTIME -> Tables.SERIES.RUNTIME;
      case LAST_WATCHED -> lastWatchedField();
    };
  }

  private SortField<?> buildOrderBy(MediaFilter filter) {
    var direction = filter.getSortDirection();

    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT.sort(direction);
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON.sort(direction);
      case RELEASE_DATE -> Tables.SERIES.FIRST_AIR_DATE.sort(direction).nullsLast();
      case RUNTIME -> Tables.SERIES.RUNTIME.sort(direction).nullsLast();
      case LAST_WATCHED -> lastWatchedField().sort(direction).nullsLast();
    };
  }
}
