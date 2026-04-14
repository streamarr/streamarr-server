package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SeriesRepositoryCustom {

  Optional<Series> findByTmdbId(String tmdbId);

  List<Series> seekWithFilter(MediaPaginationOptions options);

  List<Series> findFirstWithFilter(MediaPaginationOptions options);

  Map<UUID, Instant> findLastWatchedBySeriesIds(Collection<UUID> seriesIds);
}
