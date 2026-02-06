package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Series;
import java.util.Optional;

public interface SeriesRepositoryCustom {

  Optional<Series> findByTmdbId(String tmdbId);
}
