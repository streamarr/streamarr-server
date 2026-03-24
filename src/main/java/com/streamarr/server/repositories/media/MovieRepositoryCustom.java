package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.List;
import java.util.Optional;

public interface MovieRepositoryCustom {

  List<Movie> seekWithFilter(MediaPaginationOptions options);

  List<Movie> findFirstWithFilter(MediaPaginationOptions options);

  Optional<Movie> findByTmdbId(String tmdbId);
}
