package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MovieRepositoryCustom {

  /** The Library's movies the filter admits beyond the seek, in the filter's sort order. */
  List<Row<Movie>> rowsInLibrary(MediaFilter filter, Seek seek);

  /** The movie's row in its Library's list under the filter, when the filter admits it. */
  Optional<Row<Movie>> rowInLibrary(UUID itemId, MediaFilter filter);

  Optional<Movie> findInLibraryByTmdbId(UUID libraryId, String tmdbId);

  /** Returns the given movie ids whose movie has no provider id. */
  Set<UUID> findUnmatchedMovieIds(Collection<UUID> movieIds);
}
