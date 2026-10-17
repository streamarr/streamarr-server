package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MovieRepositoryCustom {

  List<Movie> seekWithFilter(MediaPaginationOptions options);

  List<Movie> findFirstWithFilter(MediaPaginationOptions options);

  Optional<Movie> findLetterJumpPredecessor(MediaFilter filter);

  Optional<Movie> findInLibraryByTmdbId(UUID libraryId, String tmdbId);

  /** Returns the given movie ids whose movie has no provider id. */
  Set<UUID> findUnmatchedMovieIds(Collection<UUID> movieIds);
}
