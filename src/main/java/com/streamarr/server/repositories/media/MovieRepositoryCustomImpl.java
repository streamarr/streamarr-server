package com.streamarr.server.repositories.media;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.SortOrder;

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
      filter = reverseFilter(filter);
    }

    var orderByColumns =
        new SortField[] {
          buildOrderBy(filter), Tables.BASE_COLLECTABLE.ID.sort(filter.getSortDirection())
        };

    var seekCondition =
        buildSeekCondition(
            filter, sortField(filter), filter.getPreviousSortFieldValue(), options.getCursorId());

    var query =
        context
            .select()
            .from(Tables.MOVIE)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.MOVIE.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .where(seekCondition)
            .and(libraryCondition(filter))
            .and(JooqQueryHelper.startLetterCondition(
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

  private MediaFilter reverseFilter(MediaFilter filter) {
    if (filter.getSortDirection().equals(SortOrder.DESC)) {
      return filter.toBuilder().sortDirection(SortOrder.ASC).build();
    }

    return filter.toBuilder().sortDirection(SortOrder.DESC).build();
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
            .where(libraryCondition(options.getMediaFilter()))
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

    var genreIds = filter.getGenreIds();
    if (genreIds != null && !genreIds.isEmpty()) {
      condition =
          condition.and(
              Tables.MOVIE.ID.in(
                  select(Tables.MOVIE_GENRE.MOVIE_ID)
                      .from(Tables.MOVIE_GENRE)
                      .where(Tables.MOVIE_GENRE.GENRE_ID.in(genreIds))));
    }

    var years = filter.getYears();
    if (years != null && !years.isEmpty()) {
      var yearCondition =
          years.stream()
              .map(
                  year ->
                      Tables.MOVIE.RELEASE_DATE.between(
                          LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
              .reduce(Condition::or)
              .orElse(noCondition());
      condition = condition.and(yearCondition);
    }

    var contentRatings = filter.getContentRatings();
    if (contentRatings != null && !contentRatings.isEmpty()) {
      condition = condition.and(Tables.MOVIE.CONTENT_RATING_VALUE.in(contentRatings));
    }

    var studioIds = filter.getStudioIds();
    if (studioIds != null && !studioIds.isEmpty()) {
      condition =
          condition.and(
              Tables.MOVIE.ID.in(
                  select(Tables.MOVIE_COMPANY.MOVIE_ID)
                      .from(Tables.MOVIE_COMPANY)
                      .where(Tables.MOVIE_COMPANY.COMPANY_ID.in(studioIds))));
    }

    var directorIds = filter.getDirectorIds();
    if (directorIds != null && !directorIds.isEmpty()) {
      condition =
          condition.and(
              Tables.MOVIE.ID.in(
                  select(Tables.MOVIE_DIRECTOR.MOVIE_ID)
                      .from(Tables.MOVIE_DIRECTOR)
                      .where(Tables.MOVIE_DIRECTOR.PERSON_ID.in(directorIds))));
    }

    var castMemberIds = filter.getCastMemberIds();
    if (castMemberIds != null && !castMemberIds.isEmpty()) {
      condition =
          condition.and(
              Tables.MOVIE.ID.in(
                  select(Tables.MOVIE_PERSON.MOVIE_ID)
                      .from(Tables.MOVIE_PERSON)
                      .where(Tables.MOVIE_PERSON.PERSON_ID.in(castMemberIds))));
    }

    if (Boolean.TRUE.equals(filter.getUnmatched())) {
      condition =
          condition.and(
              not(
                  Tables.BASE_COLLECTABLE.ID.in(
                      select(Tables.EXTERNAL_IDENTIFIER.ENTITY_ID)
                          .from(Tables.EXTERNAL_IDENTIFIER))));
    }

    return condition;
  }

  private Condition libraryCondition(MediaFilter filter) {
    var libraryId = filter.getLibraryId();
    return libraryId != null ? Tables.BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId) : noCondition();
  }

  private Field<?> sortField(MediaFilter filter) {
    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT;
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON;
      case RELEASE_DATE -> Tables.MOVIE.RELEASE_DATE;
      case RUNTIME -> Tables.MOVIE.RUNTIME;
    };
  }

  private boolean isNullableSortField(OrderMediaBy sortBy) {
    return sortBy == OrderMediaBy.RELEASE_DATE || sortBy == OrderMediaBy.RUNTIME;
  }

  private Object coerceSortValue(MediaFilter filter) {
    var value = filter.getPreviousSortFieldValue();
    if (value == null) {
      return null;
    }
    return switch (filter.getSortBy()) {
      case RELEASE_DATE -> value instanceof LocalDate d ? d : LocalDate.parse(value.toString());
      case RUNTIME -> value instanceof Integer i ? i : Integer.parseInt(value.toString());
      default -> value;
    };
  }

  @SuppressWarnings("unchecked")
  private Condition buildSeekCondition(
      MediaFilter filter, Field<?> sortCol, Object sortValue, Optional<java.util.UUID> cursorId) {
    var idField = Tables.BASE_COLLECTABLE.ID;
    var coercedValue = coerceSortValue(filter);
    var cursorIdValue = cursorId.orElse(null);
    var isAsc = filter.getSortDirection() == SortOrder.ASC;

    if (!isNullableSortField(filter.getSortBy()) || coercedValue != null) {
      var orderByColumns =
          new SortField[] {buildOrderBy(filter), idField.sort(filter.getSortDirection())};
      var fields = Arrays.stream(orderByColumns).map(SortField::$field).toList();
      var seekValues = new Object[] {coercedValue, cursorIdValue};
      return isAsc ? row(fields).greaterOrEqual(seekValues) : row(fields).lessOrEqual(seekValues);
    }

    // Cursor value is NULL — we're in the NULLS LAST region
    // All non-null sort values come before us, so we only need to seek within nulls by ID
    var typedCol = (Field<Object>) sortCol;
    if (isAsc) {
      // NULLS LAST + ASC: null region is at the end
      return typedCol.isNull().and(idField.greaterOrEqual(cursorIdValue));
    }
    // NULLS LAST + DESC: null region is at the end (reversed)
    return typedCol.isNull().and(idField.lessOrEqual(cursorIdValue));
  }

  private SortField<?> buildOrderBy(MediaFilter filter) {
    var direction = filter.getSortDirection();

    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT.sort(direction);
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON.sort(direction);
      case RELEASE_DATE -> Tables.MOVIE.RELEASE_DATE.sort(direction).nullsLast();
      case RUNTIME -> Tables.MOVIE.RUNTIME.sort(direction).nullsLast();
    };
  }
}
