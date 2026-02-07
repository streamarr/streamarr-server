package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import java.util.List;
import java.util.Optional;

public interface SeriesRepositoryCustom {

  Optional<Series> findByTmdbId(String tmdbId);

  List<Series> seekWithFilter(MediaPaginationOptions options);

  List<Series> findFirstWithFilter(MediaPaginationOptions options);
}
