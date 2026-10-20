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
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

@RequiredArgsConstructor
public class SeriesRepositoryCustomImpl implements SeriesRepositoryCustom {

  private final DSLContext context;
  private final EntityManager entityManager;

  @Override
  public List<Row<Series>> rowsInLibrary(MediaFilter filter, Seek seek) {
    return rows().beyond(sortField(filter), listCondition(filter), seek);
  }

  @Override
  public Optional<Row<Series>> rowInLibrary(UUID itemId, MediaFilter filter) {
    return rows().of(itemId, sortField(filter), listCondition(filter));
  }

  private LibraryItemRows<Series> rows() {
    return LibraryItemRows.<Series>builder()
        .dsl(context)
        .entityManager(entityManager)
        .kindTable(SERIES)
        .kindId(SERIES.ID)
        .type(Series.class)
        .build();
  }

  private Condition listCondition(MediaFilter filter) {
    return JooqQueryHelper.libraryCondition(filter.getLibraryId())
        .and(
            JooqQueryHelper.startLetterFilterCondition(filter.getStartLetter(), filter.getSortBy()))
        .and(filterConditions(filter));
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
}
