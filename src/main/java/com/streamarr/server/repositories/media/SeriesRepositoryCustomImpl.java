package com.streamarr.server.repositories.media;

import static com.streamarr.server.domain.streaming.WatchStatus.UNWATCHED;
import static com.streamarr.server.domain.streaming.WatchStatus.WATCHED;
import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;
import static com.streamarr.server.jooq.generated.tables.ExternalIdentifier.EXTERNAL_IDENTIFIER;
import static com.streamarr.server.jooq.generated.tables.Season.SEASON;
import static com.streamarr.server.jooq.generated.tables.Series.SERIES;
import static com.streamarr.server.jooq.generated.tables.SeriesCompany.SERIES_COMPANY;
import static com.streamarr.server.jooq.generated.tables.SeriesDirector.SERIES_DIRECTOR;
import static com.streamarr.server.jooq.generated.tables.SeriesGenre.SERIES_GENRE;
import static com.streamarr.server.jooq.generated.tables.SeriesPerson.SERIES_PERSON;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.LastWatchedExpression;
import com.streamarr.server.repositories.WatchStateConditions;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

@RequiredArgsConstructor
public class SeriesRepositoryCustomImpl implements SeriesRepositoryCustom {

  private final DSLContext context;
  private final EntityManager entityManager;

  public List<Series> seekWithFilter(MediaPaginationOptions options) {

    var shouldReverse =
        options.getPaginationOptions().getPaginationDirection().equals(PaginationDirection.REVERSE);
    var filter = options.getMediaFilter();

    if (shouldReverse) {
      filter = JooqQueryHelper.reverseFilter(filter);
    }

    var orderByColumns =
        new SortField[] {buildOrderBy(filter), BASE_COLLECTABLE.ID.sort(filter.getSortDirection())};

    var seekCondition =
        JooqQueryHelper.buildSeekCondition(
            filter, sortField(filter), orderByColumns, options.getCursorId().orElseThrow());

    var query =
        context
            .select()
            .from(SERIES)
            .innerJoin(BASE_COLLECTABLE)
            .on(SERIES.ID.eq(BASE_COLLECTABLE.ID))
            .where(seekCondition)
            .and(JooqQueryHelper.libraryCondition(filter.getLibraryId()))
            .and(
                JooqQueryHelper.startLetterCursorPageCondition(
                    filter.getStartLetter(), filter.getSortBy()))
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

  @Override
  public Set<UUID> findUnmatchedSeriesIds(Collection<UUID> seriesIds) {
    return context
        .select(SERIES.ID)
        .from(SERIES)
        .where(SERIES.ID.in(seriesIds))
        .andNotExists(
            selectOne()
                .from(EXTERNAL_IDENTIFIER)
                .where(EXTERNAL_IDENTIFIER.ENTITY_ID.eq(SERIES.ID)))
        .fetchSet(SERIES.ID);
  }

  public Optional<Series> findInLibraryByTmdbId(UUID libraryId, String tmdbId) {
    var query =
        context
            .select(SERIES.asterisk(), BASE_COLLECTABLE.asterisk())
            .from(SERIES)
            .innerJoin(BASE_COLLECTABLE)
            .on(SERIES.ID.eq(BASE_COLLECTABLE.ID))
            .join(EXTERNAL_IDENTIFIER)
            .on(EXTERNAL_IDENTIFIER.ENTITY_ID.eq(SERIES.ID))
            .where(
                EXTERNAL_IDENTIFIER
                    .EXTERNAL_SOURCE_TYPE
                    .eq(inline(ExternalSourceType.TMDB))
                    .and(EXTERNAL_IDENTIFIER.EXTERNAL_ID.eq(tmdbId))
                    .and(BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId)))
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
          BASE_COLLECTABLE.ID.sort(options.getMediaFilter().getSortDirection())
        };

    var query =
        context
            .select()
            .from(SERIES)
            .innerJoin(BASE_COLLECTABLE)
            .on(SERIES.ID.eq(BASE_COLLECTABLE.ID))
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

  public Optional<Series> findLetterJumpPredecessor(MediaFilter filter) {
    var predecessorCondition =
        JooqQueryHelper.letterJumpPredecessorCondition(
            filter.getStartLetter(), filter.getSortDirection());
    if (predecessorCondition.isEmpty()) {
      return Optional.empty();
    }

    var reversed = JooqQueryHelper.reverseFilter(filter);
    var orderByColumns =
        new SortField[] {
          buildOrderBy(reversed), BASE_COLLECTABLE.ID.sort(reversed.getSortDirection())
        };

    var query =
        context
            .select()
            .from(SERIES)
            .innerJoin(BASE_COLLECTABLE)
            .on(SERIES.ID.eq(BASE_COLLECTABLE.ID))
            .where(predecessorCondition.get())
            .and(JooqQueryHelper.libraryCondition(filter.getLibraryId()))
            .and(filterConditions(filter))
            .orderBy(orderByColumns)
            .limit(1);

    var results = JooqQueryHelper.nativeQuery(entityManager, query, Series.class);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
  }

  private Condition filterConditions(MediaFilter filter) {
    var condition = noCondition();

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                SERIES.ID,
                SERIES_GENRE,
                SERIES_GENRE.SERIES_ID,
                SERIES_GENRE.GENRE_ID,
                filter.getGenreIds()));

    condition =
        condition.and(JooqQueryHelper.yearCondition(SERIES.FIRST_AIR_DATE, filter.getYears()));

    condition =
        condition.and(
            JooqQueryHelper.contentRatingCondition(
                SERIES.CONTENT_RATING_VALUE, filter.getContentRatings()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                SERIES.ID,
                SERIES_COMPANY,
                SERIES_COMPANY.SERIES_ID,
                SERIES_COMPANY.COMPANY_ID,
                filter.getStudioIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                SERIES.ID,
                SERIES_DIRECTOR,
                SERIES_DIRECTOR.SERIES_ID,
                SERIES_DIRECTOR.PERSON_ID,
                filter.getDirectorIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                SERIES.ID,
                SERIES_PERSON,
                SERIES_PERSON.SERIES_ID,
                SERIES_PERSON.PERSON_ID,
                filter.getCastMemberIds()));

    condition = condition.and(JooqQueryHelper.unmatchedCondition(filter.getUnmatched()));
    condition = condition.and(watchStatusCondition(filter));

    return condition;
  }

  private Condition watchStatusCondition(MediaFilter filter) {
    var watchStatus = filter.getWatchStatus();
    if (watchStatus == null) {
      return noCondition();
    }

    var profileId = requireProfileId(filter.getProfileId());

    var fullyWatched = seriesHasEpisodes().and(not(anyEpisodeOfSeriesIsNot(profileId, WATCHED)));
    var fullyUnwatched = not(anyEpisodeOfSeriesIsNot(profileId, UNWATCHED));

    return switch (watchStatus) {
      case WATCHED -> fullyWatched;
      case IN_PROGRESS -> not(fullyWatched).and(not(fullyUnwatched));
      case UNWATCHED -> fullyUnwatched;
    };
  }

  private static Condition seriesHasEpisodes() {
    return anyEpisodeOfSeries(noCondition());
  }

  private static Condition anyEpisodeOfSeriesIsNot(UUID profileId, WatchStatus status) {
    return anyEpisodeOfSeries(
        not(WatchStateConditions.watchStatusIs(profileId, EPISODE.ID, status)));
  }

  private static Condition anyEpisodeOfSeries(Condition episodeCondition) {
    return exists(
        select(EPISODE.ID)
            .from(EPISODE)
            .innerJoin(SEASON)
            .on(EPISODE.SEASON_ID.eq(SEASON.ID))
            .where(SEASON.SERIES_ID.eq(BASE_COLLECTABLE.ID).and(episodeCondition)));
  }

  private static Field<OffsetDateTime> lastWatchedAt(MediaFilter filter) {
    return LastWatchedExpression.lastWatchedAt(
        requireProfileId(filter.getProfileId()), CollectableScope.SERIES, BASE_COLLECTABLE.ID);
  }

  private static UUID requireProfileId(@NonNull UUID profileId) {
    return profileId;
  }

  private Field<?> sortField(MediaFilter filter) {
    return switch (filter.getSortBy()) {
      case TITLE -> JooqQueryHelper.titleSortField();
      case ADDED -> BASE_COLLECTABLE.CREATED_ON;
      case RELEASE_DATE -> SERIES.FIRST_AIR_DATE;
      case RUNTIME -> SERIES.RUNTIME;
      case LAST_WATCHED -> lastWatchedAt(filter);
    };
  }

  private SortField<?> buildOrderBy(MediaFilter filter) {
    var direction = filter.getSortDirection();

    return switch (filter.getSortBy()) {
      case TITLE -> JooqQueryHelper.titleSortField().sort(direction);
      case ADDED -> BASE_COLLECTABLE.CREATED_ON.sort(direction);
      case RELEASE_DATE -> SERIES.FIRST_AIR_DATE.sort(direction).nullsLast();
      case RUNTIME -> SERIES.RUNTIME.sort(direction).nullsLast();
      case LAST_WATCHED -> lastWatchedAt(filter).sort(direction).nullsLast();
    };
  }
}
