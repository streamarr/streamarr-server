package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Genre;
import java.util.List;
import java.util.UUID;

public interface GenreRepositoryCustom {

  boolean insertIfAbsent(String sourceId, String name);

  List<Genre> findByMovieId(UUID movieId);

  List<Genre> findBySeriesId(UUID seriesId);
}
