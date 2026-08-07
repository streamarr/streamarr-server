package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Company;
import java.util.List;
import java.util.UUID;

public interface CompanyRepositoryCustom {

  boolean insertIfAbsent(String sourceId, String name);

  List<Company> findByMovieId(UUID movieId);

  List<Company> findBySeriesId(UUID seriesId);
}
