package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.List;
import java.util.Optional;

public class FakeMovieRepository extends FakeJpaRepository<Movie> implements MovieRepository {

  @Override
  public Optional<Movie> findByTmdbId(String tmdbId) {
    return database.values().stream()
        .filter(
            movie ->
                movie.getExternalIds().stream().anyMatch(id -> id.getExternalId().equals(tmdbId)))
        .findFirst();
  }

  @Override
  public List<Movie> findFirstWithFilter(MediaPaginationOptions options) {
    return database.values().stream().toList();
  }

  @Override
  public List<Movie> seekWithFilter(MediaPaginationOptions options) {
    return database.values().stream().toList();
  }
}
