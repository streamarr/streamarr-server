package com.streamarr.server.fakes;

import static java.util.Comparator.comparingInt;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.repositories.media.EpisodeRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeEpisodeRepository extends FakeJpaRepository<Episode> implements EpisodeRepository {

  private static boolean inSeasons(Episode episode, Collection<UUID> seasonIds) {
    return episode.getSeason() != null && seasonIds.contains(episode.getSeason().getId());
  }

  @Override
  public List<Episode> findBySeasonId(UUID seasonId) {
    return findBySeasonIdIn(List.of(seasonId)).stream()
        .sorted(comparingInt(Episode::getEpisodeNumber))
        .toList();
  }

  @Override
  public List<Episode> findBySeasonIdIn(Collection<UUID> seasonIds) {
    return database.values().stream().filter(episode -> inSeasons(episode, seasonIds)).toList();
  }

  @Override
  public Map<UUID, List<UUID>> findEpisodeIdsBySeasonIds(Collection<UUID> seasonIds) {
    return database.values().stream()
        .filter(episode -> inSeasons(episode, seasonIds))
        .collect(
            Collectors.groupingBy(
                ep -> ep.getSeason().getId(),
                Collectors.mapping(Episode::getId, Collectors.toList())));
  }

  @Override
  public Optional<Episode> findBySeasonIdAndEpisodeNumber(UUID seasonId, int episodeNumber) {
    return database.values().stream()
        .filter(
            episode ->
                inSeasons(episode, List.of(seasonId))
                    && episode.getEpisodeNumber() == episodeNumber)
        .findFirst();
  }
}
