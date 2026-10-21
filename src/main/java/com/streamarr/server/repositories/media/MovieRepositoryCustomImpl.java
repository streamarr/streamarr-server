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
import static org.jooq.impl.DSL.selectOne;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
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
public class MovieRepositoryCustomImpl implements MovieRepositoryCustom {

  private final DSLContext context;
  private final EntityManager entityManager;

  @Override
  public List<Row<Movie>> rowsInLibrary(MediaFilter filter, Seek seek) {
    return rows().beyond(keysetOf(filter), listCondition(filter), seek);
  }

  @Override
  public Optional<Row<Movie>> rowInLibrary(UUID itemId, MediaFilter filter) {
    return rows().of(itemId, keysetOf(filter), listCondition(filter));
  }

  private LibraryItemRows<Movie> rows() {
    return LibraryItemRows.<Movie>builder()
        .dsl(context)
        .entityManager(entityManager)
        .kindTable(MOVIE)
        .kindId(MOVIE.ID)
        .type(Movie.class)
        .build();
  }

  private Condition listCondition(MediaFilter filter) {
    return JooqQueryHelper.libraryCondition(filter.getLibraryId())
        .and(
            JooqQueryHelper.startLetterFilterCondition(filter.getStartLetter(), filter.getSortBy()))
        .and(filterConditions(filter));
  }

  public Optional<Movie> findInLibraryByTmdbId(UUID libraryId, String tmdbId) {
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
                    .and(EXTERNAL_IDENTIFIER.EXTERNAL_ID.eq(tmdbId))
                    .and(BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId)))
            .limit(1);

    var results = JooqQueryHelper.nativeQuery(entityManager, query, Movie.class);

    if (results.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(results.getFirst());
  }

  @Override
  public Set<UUID> findUnmatchedMovieIds(Collection<UUID> movieIds) {
    return context
        .select(MOVIE.ID)
        .from(MOVIE)
        .where(MOVIE.ID.in(movieIds))
        .andNotExists(
            selectOne().from(EXTERNAL_IDENTIFIER).where(EXTERNAL_IDENTIFIER.ENTITY_ID.eq(MOVIE.ID)))
        .fetchSet(MOVIE.ID);
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

  private Keyset<?> keysetOf(MediaFilter filter) {
    return switch (filter.getSortBy()) {
      case TITLE -> LibraryItemRows.keyset(JooqQueryHelper.titleSortField(), BASE_COLLECTABLE.ID);
      case ADDED -> LibraryItemRows.keyset(BASE_COLLECTABLE.CREATED_ON, BASE_COLLECTABLE.ID);
      case RELEASE_DATE -> LibraryItemRows.keyset(MOVIE.RELEASE_DATE, MOVIE.ID);
      case RUNTIME -> LibraryItemRows.keyset(MOVIE.RUNTIME, MOVIE.ID);
      case LAST_WATCHED -> LibraryItemRows.keyset(lastWatchedAt(filter), BASE_COLLECTABLE.ID);
    };
  }
}
