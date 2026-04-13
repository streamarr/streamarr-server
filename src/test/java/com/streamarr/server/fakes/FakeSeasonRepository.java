package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Season;
import com.streamarr.server.repositories.media.SeasonRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
  public List<Season> findBySeriesIdIn(Collection<UUID> seriesIds) {
    return database.values().stream()
        .filter(
            season -> season.getSeries() != null && seriesIds.contains(season.getSeries().getId()))
        .toList();
  }

  @Override
  public Map<UUID, List<UUID>> findSeasonIdsBySeriesIds(Collection<UUID> seriesIds) {
    return database.values().stream()
        .filter(
            season -> season.getSeries() != null && seriesIds.contains(season.getSeries().getId()))
        .collect(
            Collectors.groupingBy(
                s -> s.getSeries().getId(),
                Collectors.mapping(Season::getId, Collectors.toList())));
  }

  @Override
  public List<Season> findBySeriesId(UUID seriesId) {
    return database.values().stream()
        .filter(season -> season.getSeries() != null && seriesId.equals(season.getSeries().getId()))
        .toList();
  }
}
