package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Genre.GENRE;
import static com.streamarr.server.jooq.generated.tables.MovieGenre.MOVIE_GENRE;
import static com.streamarr.server.jooq.generated.tables.SeriesGenre.SERIES_GENRE;

import com.streamarr.server.domain.metadata.Genre;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class GenreRepositoryCustomImpl implements GenreRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public boolean insertIfAbsent(String sourceId, String name) {
    var rowsAffected =
        dsl.insertInto(GENRE)
            .set(GENRE.SOURCE_ID, sourceId)
            .set(GENRE.NAME, name)
            .onConflict(GENRE.SOURCE_ID)
            .doNothing()
            .execute();
    return rowsAffected > 0;
  }

  @Override
  public List<Genre> findByMovieId(UUID movieId) {
    var query =
        dsl.select(GENRE.asterisk())
            .from(GENRE)
            .innerJoin(MOVIE_GENRE)
            .on(MOVIE_GENRE.GENRE_ID.eq(GENRE.ID))
            .where(MOVIE_GENRE.MOVIE_ID.eq(movieId));

    return JooqQueryHelper.nativeQuery(entityManager, query, Genre.class);
  }

  @Override
  public List<Genre> findBySeriesId(UUID seriesId) {
    var query =
        dsl.select(GENRE.asterisk())
            .from(GENRE)
            .innerJoin(SERIES_GENRE)
            .on(SERIES_GENRE.GENRE_ID.eq(GENRE.ID))
            .where(SERIES_GENRE.SERIES_ID.eq(seriesId));

    return JooqQueryHelper.nativeQuery(entityManager, query, Genre.class);
  }
}
