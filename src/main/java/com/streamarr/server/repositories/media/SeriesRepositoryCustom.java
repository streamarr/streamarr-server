package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.pagination.ItemSortValue;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SeriesRepositoryCustom {

  Optional<ItemSortValue> findItemPosition(UUID itemId, MediaFilter filter);

  Optional<Series> findInLibraryByTmdbId(UUID libraryId, String tmdbId);

  List<Series> seekWithFilter(MediaPaginationOptions options);

  List<Series> findFirstWithFilter(MediaPaginationOptions options);

  Optional<Series> findLetterJumpPredecessor(MediaFilter filter);

  /** Returns the given series ids whose series has no provider id. */
  Set<UUID> findUnmatchedSeriesIds(Collection<UUID> seriesIds);
}
