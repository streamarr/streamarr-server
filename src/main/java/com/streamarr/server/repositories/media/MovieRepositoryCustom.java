package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface MovieRepositoryCustom {

  List<Movie> seekWithFilter(MediaPaginationOptions options);

  List<Movie> findFirstWithFilter(MediaPaginationOptions options);

  Optional<Movie> findByTmdbId(String tmdbId);

  Map<UUID, Instant> findLastWatchedByMovieIds(Collection<UUID> movieIds);
}
