package com.streamarr.server.fakes;

import static java.util.Comparator.comparingInt;

import com.streamarr.server.domain.media.Season;
import com.streamarr.server.repositories.media.SeasonRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeSeasonRepository extends FakeJpaRepository<Season> implements SeasonRepository {

  private static boolean inSeries(Season season, Collection<UUID> seriesIds) {
    return season.getSeries() != null && seriesIds.contains(season.getSeries().getId());
  }

  @Override
  public Optional<Season> findBySeriesIdAndSeasonNumber(UUID seriesId, int seasonNumber) {
    return database.values().stream()
        .filter(
            season ->
                inSeries(season, List.of(seriesId)) && season.getSeasonNumber() == seasonNumber)
        .findFirst();
  }

  @Override
  public List<Season> findBySeriesIdIn(Collection<UUID> seriesIds) {
    return database.values().stream().filter(season -> inSeries(season, seriesIds)).toList();
  }

  @Override
  public Map<UUID, List<UUID>> findSeasonIdsBySeriesIds(Collection<UUID> seriesIds) {
    return database.values().stream()
        .filter(season -> inSeries(season, seriesIds))
        .collect(
            Collectors.groupingBy(
                s -> s.getSeries().getId(),
                Collectors.mapping(Season::getId, Collectors.toList())));
  }

  @Override
  public List<Season> findBySeriesId(UUID seriesId) {
    return findBySeriesIdIn(List.of(seriesId)).stream()
        .sorted(comparingInt(Season::getSeasonNumber))
        .toList();
  }
}
