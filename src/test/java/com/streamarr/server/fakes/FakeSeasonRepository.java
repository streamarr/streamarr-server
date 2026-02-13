package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Season;
import com.streamarr.server.repositories.media.SeasonRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeSeasonRepository extends FakeJpaRepository<Season> implements SeasonRepository {

  @Override
  public Optional<Season> findBySeriesIdAndSeasonNumber(UUID seriesId, int seasonNumber) {
    return database.values().stream()
        .filter(
            season ->
                season.getSeries() != null
                    && seriesId.equals(season.getSeries().getId())
                    && season.getSeasonNumber() == seasonNumber)
        .findFirst();
  }

  @Override
  public List<Season> findBySeriesId(UUID seriesId) {
    return database.values().stream()
        .filter(
            season -> season.getSeries() != null && seriesId.equals(season.getSeries().getId()))
        .toList();
  }
}
