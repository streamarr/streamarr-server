package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SeriesRepositoryCustom {

  /** The Library's series the filter admits beyond the seek, in the filter's sort order. */
  List<Row<Series>> rowsInLibrary(MediaFilter filter, Seek seek);

  /** The series's row in its Library's list under the filter, when the filter admits it. */
  Optional<Row<Series>> rowInLibrary(UUID itemId, MediaFilter filter);

  Optional<Series> findInLibraryByTmdbId(UUID libraryId, String tmdbId);

  /** Returns the given series ids whose series has no provider id. */
  Set<UUID> findUnmatchedSeriesIds(Collection<UUID> seriesIds);
}
