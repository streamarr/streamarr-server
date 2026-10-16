package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.ExternalIdentifier.EXTERNAL_IDENTIFIER;
import static com.streamarr.server.jooq.generated.tables.Movie.MOVIE;
import static com.streamarr.server.jooq.generated.tables.MovieCompany.MOVIE_COMPANY;
import static com.streamarr.server.jooq.generated.tables.MovieDirector.MOVIE_DIRECTOR;
import static com.streamarr.server.jooq.generated.tables.MovieGenre.MOVIE_GENRE;
import static com.streamarr.server.jooq.generated.tables.MoviePerson.MOVIE_PERSON;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.LastWatchedExpression;
import com.streamarr.server.repositories.WatchStateConditions;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

@RequiredArgsConstructor
public class MovieRepositoryCustomImpl implements MovieRepositoryCustom {

  private final DSLContext context;
  private final EntityManager entityManager;

  public List<Movie> seekWithFilter(MediaPaginationOptions options) {

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
            .from(MOVIE)
            .innerJoin(BASE_COLLECTABLE)
            .on(MOVIE.ID.eq(BASE_COLLECTABLE.ID))
            .where(seekCondition)
            .and(JooqQueryHelper.libraryCondition(filter.getLibraryId()))
            .and(
                JooqQueryHelper.startLetterCursorPageCondition(
                    filter.getStartLetter(), filter.getSortBy()))
            .and(filterConditions(filter))
            .orderBy(orderByColumns)
            // N+2 (Allows us to efficiently check if there are items before AND after N)
            .limit(options.getPaginationOptions().getLimit() + 2);

    var results = JooqQueryHelper.nativeQuery(entityManager, query, Movie.class);

    if (shouldReverse) {
      Collections.reverse(results);
    }

    return results;
  }

  public Optional<Movie> findByTmdbId(String tmdbId) {
    var query =
        context
            .select(MOVIE.asterisk(), BASE_COLLECTABLE.asterisk())
            .from(MOVIE)
            .innerJoin(BASE_COLLECTABLE)
            .on(MOVIE.ID.eq(BASE_COLLECTABLE.ID))
            .join(EXTERNAL_IDENTIFIER)
            .on(EXTERNAL_IDENTIFIER.ENTITY_ID.eq(MOVIE.ID))
            .where(
                EXTERNAL_IDENTIFIER
                    .EXTERNAL_SOURCE_TYPE
                    .eq(inline(ExternalSourceType.TMDB))
                    .and(EXTERNAL_IDENTIFIER.EXTERNAL_ID.eq(tmdbId)))
            .limit(1);

    var results = JooqQueryHelper.nativeQuery(entityManager, query, Movie.class);

    if (results.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(results.getFirst());
  }

  public List<Movie> findFirstWithFilter(MediaPaginationOptions options) {

    var orderByColumns =
        new SortField[] {
          buildOrderBy(options.getMediaFilter()),
          BASE_COLLECTABLE.ID.sort(options.getMediaFilter().getSortDirection())
        };

    var query =
        context
            .select()
            .from(MOVIE)
            .innerJoin(BASE_COLLECTABLE)
            .on(MOVIE.ID.eq(BASE_COLLECTABLE.ID))
            .where(JooqQueryHelper.libraryCondition(options.getMediaFilter().getLibraryId()))
            .and(
                JooqQueryHelper.startLetterCondition(
                    options.getMediaFilter().getStartLetter(),
                    options.getMediaFilter().getSortDirection(),
                    options.getMediaFilter().getSortBy()))
            .and(filterConditions(options.getMediaFilter()))
            .orderBy(orderByColumns)
            .limit(options.getPaginationOptions().getLimit() + 1);

    return JooqQueryHelper.nativeQuery(entityManager, query, Movie.class);
  }

  public Optional<Movie> findLetterJumpPredecessor(MediaFilter filter) {
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
            .from(MOVIE)
            .innerJoin(BASE_COLLECTABLE)
            .on(MOVIE.ID.eq(BASE_COLLECTABLE.ID))
            .where(predecessorCondition.get())
            .and(JooqQueryHelper.libraryCondition(filter.getLibraryId()))
            .and(filterConditions(filter))
            .orderBy(orderByColumns)
            .limit(1);

    var results = JooqQueryHelper.nativeQuery(entityManager, query, Movie.class);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
  }

  private Condition filterConditions(MediaFilter filter) {
    var condition = noCondition();

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                MOVIE.ID,
                MOVIE_GENRE,
                MOVIE_GENRE.MOVIE_ID,
                MOVIE_GENRE.GENRE_ID,
                filter.getGenreIds()));

    condition = condition.and(JooqQueryHelper.yearCondition(MOVIE.RELEASE_DATE, filter.getYears()));

    condition =
        condition.and(
            JooqQueryHelper.contentRatingCondition(
                MOVIE.CONTENT_RATING_VALUE, filter.getContentRatings()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                MOVIE.ID,
                MOVIE_COMPANY,
                MOVIE_COMPANY.MOVIE_ID,
                MOVIE_COMPANY.COMPANY_ID,
                filter.getStudioIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                MOVIE.ID,
                MOVIE_DIRECTOR,
                MOVIE_DIRECTOR.MOVIE_ID,
                MOVIE_DIRECTOR.PERSON_ID,
                filter.getDirectorIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                MOVIE.ID,
                MOVIE_PERSON,
                MOVIE_PERSON.MOVIE_ID,
                MOVIE_PERSON.PERSON_ID,
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

    return WatchStateConditions.watchStatusIs(profileId, BASE_COLLECTABLE.ID, watchStatus);
  }

  private static Field<OffsetDateTime> lastWatchedAt(MediaFilter filter) {
    return LastWatchedExpression.lastWatchedAt(
        requireProfileId(filter.getProfileId()),
        CollectableScope.MOVIE_OR_EPISODE,
        BASE_COLLECTABLE.ID);
  }

  private static UUID requireProfileId(@NonNull UUID profileId) {
    return profileId;
  }

  private Field<?> sortField(MediaFilter filter) {
    return switch (filter.getSortBy()) {
      case TITLE -> JooqQueryHelper.titleSortField();
      case ADDED -> BASE_COLLECTABLE.CREATED_ON;
      case RELEASE_DATE -> MOVIE.RELEASE_DATE;
      case RUNTIME -> MOVIE.RUNTIME;
      case LAST_WATCHED -> lastWatchedAt(filter);
    };
  }

  private SortField<?> buildOrderBy(MediaFilter filter) {
    var direction = filter.getSortDirection();

    return switch (filter.getSortBy()) {
      case TITLE -> JooqQueryHelper.titleSortField().sort(direction);
      case ADDED -> BASE_COLLECTABLE.CREATED_ON.sort(direction);
      case RELEASE_DATE -> MOVIE.RELEASE_DATE.sort(direction).nullsLast();
      case RUNTIME -> MOVIE.RUNTIME.sort(direction).nullsLast();
      case LAST_WATCHED -> lastWatchedAt(filter).sort(direction).nullsLast();
    };
  }
}
