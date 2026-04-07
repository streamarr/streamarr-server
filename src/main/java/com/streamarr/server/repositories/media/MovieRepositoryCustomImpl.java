package com.streamarr.server.repositories.media;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

@RequiredArgsConstructor
public class MovieRepositoryCustomImpl implements MovieRepositoryCustom {

  private final DSLContext context;
  @PersistenceContext private final EntityManager entityManager;

  public List<Movie> seekWithFilter(MediaPaginationOptions options) {

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
            .from(Tables.MOVIE)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.MOVIE.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .where(seekCondition)
            .and(JooqQueryHelper.libraryCondition(filter.getLibraryId()))
            .and(
                JooqQueryHelper.startLetterCondition(
                    filter.getStartLetter(), originalDirection, filter.getSortBy()))
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
            .select(Tables.MOVIE.asterisk(), Tables.BASE_COLLECTABLE.asterisk())
            .from(Tables.MOVIE)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.MOVIE.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .join(Tables.EXTERNAL_IDENTIFIER)
            .on(Tables.EXTERNAL_IDENTIFIER.ENTITY_ID.eq(Tables.MOVIE.ID))
            .where(
                Tables.EXTERNAL_IDENTIFIER
                    .EXTERNAL_SOURCE_TYPE
                    .eq(inline(ExternalSourceType.TMDB))
                    .and(Tables.EXTERNAL_IDENTIFIER.EXTERNAL_ID.eq(tmdbId)))
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
          Tables.BASE_COLLECTABLE.ID.sort(options.getMediaFilter().getSortDirection())
        };

    var query =
        context
            .select()
            .from(Tables.MOVIE)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.MOVIE.ID.eq(Tables.BASE_COLLECTABLE.ID))
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

  private Condition filterConditions(MediaFilter filter) {
    var condition = noCondition();

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.MOVIE.ID,
                Tables.MOVIE_GENRE,
                Tables.MOVIE_GENRE.MOVIE_ID,
                Tables.MOVIE_GENRE.GENRE_ID,
                filter.getGenreIds()));

    condition =
        condition.and(JooqQueryHelper.yearCondition(Tables.MOVIE.RELEASE_DATE, filter.getYears()));

    condition =
        condition.and(
            JooqQueryHelper.contentRatingCondition(
                Tables.MOVIE.CONTENT_RATING_VALUE, filter.getContentRatings()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.MOVIE.ID,
                Tables.MOVIE_COMPANY,
                Tables.MOVIE_COMPANY.MOVIE_ID,
                Tables.MOVIE_COMPANY.COMPANY_ID,
                filter.getStudioIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.MOVIE.ID,
                Tables.MOVIE_DIRECTOR,
                Tables.MOVIE_DIRECTOR.MOVIE_ID,
                Tables.MOVIE_DIRECTOR.PERSON_ID,
                filter.getDirectorIds()));

    condition =
        condition.and(
            JooqQueryHelper.semiJoinCondition(
                Tables.MOVIE.ID,
                Tables.MOVIE_PERSON,
                Tables.MOVIE_PERSON.MOVIE_ID,
                Tables.MOVIE_PERSON.PERSON_ID,
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

    var isWatched =
        exists(
            select(Tables.WATCH_HISTORY.ID)
                .from(Tables.WATCH_HISTORY)
                .where(
                    Tables.WATCH_HISTORY
                        .COLLECTABLE_ID
                        .eq(Tables.BASE_COLLECTABLE.ID)
                        .and(Tables.WATCH_HISTORY.USER_ID.eq(userId))
                        .and(Tables.WATCH_HISTORY.DISMISSED_AT.isNull())));

    var hasProgress =
        exists(
            select(Tables.SESSION_PROGRESS.ID)
                .from(Tables.SESSION_PROGRESS)
                .innerJoin(Tables.MEDIA_FILE)
                .on(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(Tables.MEDIA_FILE.ID))
                .where(
                    Tables.MEDIA_FILE
                        .MEDIA_ID
                        .eq(Tables.BASE_COLLECTABLE.ID)
                        .and(Tables.SESSION_PROGRESS.USER_ID.eq(userId))
                        .and(Tables.SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))));

    return switch (watchStatus) {
      case WATCHED -> isWatched;
      case IN_PROGRESS -> not(isWatched).and(hasProgress);
      case UNWATCHED -> not(isWatched).and(not(hasProgress));
    };
  }

  private Field<?> sortField(MediaFilter filter) {
    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT;
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON;
      case RELEASE_DATE -> Tables.MOVIE.RELEASE_DATE;
      case RUNTIME -> Tables.MOVIE.RUNTIME;
      case LAST_WATCHED ->
          throw new UnsupportedOperationException("LAST_WATCHED not yet implemented");
    };
  }

  private SortField<?> buildOrderBy(MediaFilter filter) {
    var direction = filter.getSortDirection();

    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT.sort(direction);
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON.sort(direction);
      case RELEASE_DATE -> Tables.MOVIE.RELEASE_DATE.sort(direction).nullsLast();
      case RUNTIME -> Tables.MOVIE.RUNTIME.sort(direction).nullsLast();
      case LAST_WATCHED ->
          throw new UnsupportedOperationException("LAST_WATCHED not yet implemented");
    };
  }
}
