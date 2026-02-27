package com.streamarr.server.repositories.media;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;

import com.streamarr.server.domain.media.Series;
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
public class SeriesRepositoryCustomImpl implements SeriesRepositoryCustom {

  private final DSLContext context;
  @PersistenceContext private final EntityManager entityManager;

  public List<Series> seekWithFilter(MediaPaginationOptions options) {

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
        buildSeekCondition(filter, sortField(filter), options.getCursorId());

    var query =
        context
            .select()
            .from(Tables.SERIES)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.SERIES.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .where(seekCondition)
            .and(JooqQueryHelper.libraryCondition(filter.getLibraryId()))
            .and(JooqQueryHelper.startLetterCondition(
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

  private MediaFilter reverseFilter(MediaFilter filter) {
    if (filter.getSortDirection().equals(SortOrder.DESC)) {
      return filter.toBuilder().sortDirection(SortOrder.ASC).build();
    }

    return filter.toBuilder().sortDirection(SortOrder.DESC).build();
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

    var years = filter.getYears();
    if (years != null && !years.isEmpty()) {
      var yearCondition =
          years.stream()
              .map(
                  year ->
                      Tables.SERIES.FIRST_AIR_DATE.between(
                          LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
              .reduce(Condition::or)
              .orElse(noCondition());
      condition = condition.and(yearCondition);
    }

    var contentRatings = filter.getContentRatings();
    if (contentRatings != null && !contentRatings.isEmpty()) {
      condition = condition.and(Tables.SERIES.CONTENT_RATING_VALUE.in(contentRatings));
    }

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

    return condition;
  }

  private Field<?> sortField(MediaFilter filter) {
    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT;
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON;
      case RELEASE_DATE -> Tables.SERIES.FIRST_AIR_DATE;
      case RUNTIME -> Tables.SERIES.RUNTIME;
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
      MediaFilter filter, Field<?> sortCol, Optional<java.util.UUID> cursorId) {
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

    var typedCol = (Field<Object>) sortCol;
    if (isAsc) {
      return typedCol.isNull().and(idField.greaterOrEqual(cursorIdValue));
    }
    return typedCol.isNull().and(idField.lessOrEqual(cursorIdValue));
  }

  private SortField<?> buildOrderBy(MediaFilter filter) {
    var direction = filter.getSortDirection();

    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE_SORT.sort(direction);
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON.sort(direction);
      case RELEASE_DATE -> Tables.SERIES.FIRST_AIR_DATE.sort(direction).nullsLast();
      case RUNTIME -> Tables.SERIES.RUNTIME.sort(direction).nullsLast();
    };
  }
}
