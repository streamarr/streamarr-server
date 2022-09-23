package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Movie_;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.jooq.generated.Tables;
import io.smallrye.mutiny.vertx.UniHelper;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.hibernate.reactive.mutiny.Mutiny;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.jooq.SortOrder;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.criteria.Root;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.row;


@RequiredArgsConstructor
public class MovieRepositoryCustomImpl implements MovieRepositoryCustom {

    private final DSLContext context;
    @PersistenceContext
    private final EntityManager entityManager;

    private final Mutiny.SessionFactory sessionFactory;

    public Future<Movie> saveAsync(Movie movie) {

        if (movie.getId() == null) {
            return UniHelper.toFuture(sessionFactory.withTransaction(session ->
                session.persist(movie)
                    .chain(session::flush)
                    .replaceWith(movie)
            )).onFailure(System.out::println);
        } else {
            return UniHelper.toFuture(sessionFactory.withTransaction(session ->
                session.merge(movie)
                    .onItem()
                    .call(session::flush)));
        }
    }

    public Future<Movie> findByTmdbId(String tmdbId) {
        var cb = sessionFactory.getCriteriaBuilder();

        var query = cb.createQuery(Movie.class);

        Root<Movie> root = query.from(Movie.class);

        if (tmdbId != null && !tmdbId.trim().isEmpty()) {
            query.where(
                cb.equal(root.get(Movie_.TMDB_ID), tmdbId)
            );
        }

        return UniHelper.toFuture(sessionFactory.withSession(session -> {
            var graph = session.createEntityGraph(Movie.class);
            graph.addAttributeNodes(Movie_.FILES);
            graph.addAttributeNodes(Movie_.CAST);

            return session.createQuery(query)
                .setPlan(graph)
                .getSingleResultOrNull();
        }));
    }

    public List<Movie> seekWithFilter(MediaPaginationOptions options) {

        var shouldReverse = options.getPaginationOptions().getPaginationDirection().equals(PaginationDirection.REVERSE);
        var filter = options.getMediaFilter();

        if (shouldReverse) {
            filter = reverseFilter(filter);
        }

        var orderByColumns = new SortField[]{buildOrderBy(filter), Tables.BASE_COLLECTABLE.ID.sort(filter.getSortDirection())};
        var seekValues = new Object[]{filter.getPreviousSortFieldValue(), options.getCursorId()};

        //  WHERE (id, code) > (3, 'abc')
        //  ORDER BY id ASC, code ASC

        var fields = Arrays.stream(orderByColumns).map(SortField::$field).collect(Collectors.toList());

        var query = context.select()
            .from(Tables.MOVIE)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.MOVIE.ID.eq(Tables.BASE_COLLECTABLE.ID))
            // Reverses seek based on sort order
            .where(filter.getSortDirection().equals(SortOrder.DESC) ? row(fields).lessOrEqual(seekValues) : row(fields).greaterOrEqual(seekValues))
            .orderBy(orderByColumns)
            // N+2 (Allows us to efficiently check if there are items before AND after N)
            .limit(options.getPaginationOptions().getLimit() + 2);

        var results = nativeQuery(entityManager, query, Movie.class);

        if (shouldReverse) {
            Collections.reverse(results);
        }

        return results;
    }

    private MediaFilter reverseFilter(MediaFilter filter) {
        if (filter.getSortDirection().equals(SortOrder.DESC)) {
            return filter.toBuilder().sortDirection(SortOrder.ASC).build();
        }

        return filter.toBuilder().sortDirection(SortOrder.DESC).build();
    }

    public List<Movie> findFirstWithFilter(MediaPaginationOptions options) {

        var orderByColumns = new SortField[]{buildOrderBy(options.getMediaFilter()), Tables.BASE_COLLECTABLE.ID.sort(SortOrder.DEFAULT)};

        // TODO: reuse any logic from above?
        var query = context.select()
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

    // TODO: Move to helper class
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
