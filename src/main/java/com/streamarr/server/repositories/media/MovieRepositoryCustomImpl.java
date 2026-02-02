package com.streamarr.server.repositories.media;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.row;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
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

    if (shouldReverse) {
      filter = reverseFilter(filter);
    }

    var orderByColumns =
        new SortField[] {
          buildOrderBy(filter), Tables.BASE_COLLECTABLE.ID.sort(filter.getSortDirection())
        };
    var seekValues = new Object[] {filter.getPreviousSortFieldValue(), options.getCursorId()};

    var fields = Arrays.stream(orderByColumns).map(SortField::$field).toList();

    var query =
        context
            .select()
            .from(Tables.MOVIE)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.MOVIE.ID.eq(Tables.BASE_COLLECTABLE.ID))
            // Reverses seek based on sort order
            .where(
                filter.getSortDirection().equals(SortOrder.DESC)
                    ? row(fields).lessOrEqual(seekValues)
                    : row(fields).greaterOrEqual(seekValues))
            .orderBy(orderByColumns)
            // N+2 (Allows us to efficiently check if there are items before AND after N)
            .limit(options.getPaginationOptions().getLimit() + 2);

    var results = nativeQuery(entityManager, query, Movie.class);

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

    var results = nativeQuery(entityManager, query, Movie.class);

    if (results.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(results.get(0));
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
          buildOrderBy(options.getMediaFilter()), Tables.BASE_COLLECTABLE.ID.sort(SortOrder.DEFAULT)
        };

    var query =
        context
            .select()
            .from(Tables.MOVIE)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.MOVIE.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .orderBy(orderByColumns)
            .limit(options.getPaginationOptions().getLimit() + 1);

    return nativeQuery(entityManager, query, Movie.class);
  }

  private SortField<?> buildOrderBy(MediaFilter filter) {
    var direction = filter.getSortDirection();

    return switch (filter.getSortBy()) {
      case TITLE -> Tables.BASE_COLLECTABLE.TITLE.sort(direction);
      case ADDED -> Tables.BASE_COLLECTABLE.CREATED_ON.sort(direction);
    };
  }

  @SuppressWarnings("unchecked")
  public static <E> List<E> nativeQuery(EntityManager em, org.jooq.Query query, Class<E> type) {

    // Extract the SQL statement from the jOOQ query:
    Query result = em.createNativeQuery(query.getSQL(), type);

    // Extract the bind values from the jOOQ query:
    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      result.setParameter(i + 1, values.get(i));
    }

    return result.getResultList();
  }
}
