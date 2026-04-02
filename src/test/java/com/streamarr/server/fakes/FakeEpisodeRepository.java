package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.repositories.media.EpisodeRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeEpisodeRepository extends FakeJpaRepository<Episode> implements EpisodeRepository {

  @Override
  public List<Episode> findBySeasonId(UUID seasonId) {
    return database.values().stream()
        .filter(
            episode -> episode.getSeason() != null && seasonId.equals(episode.getSeason().getId()))
        .toList();
  }

  @Override
  public List<Episode> findBySeasonIdIn(Collection<UUID> seasonIds) {
    return database.values().stream()
        .filter(
            episode ->
                episode.getSeason() != null && seasonIds.contains(episode.getSeason().getId()))
        .toList();
  }

  @Override
  public Map<UUID, List<UUID>> findEpisodeIdsBySeasonIds(Collection<UUID> seasonIds) {
    return database.values().stream()
        .filter(
            episode ->
                episode.getSeason() != null && seasonIds.contains(episode.getSeason().getId()))
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
                episode.getSeason() != null
                    && seasonId.equals(episode.getSeason().getId())
                    && episode.getEpisodeNumber() == episodeNumber)
        .findFirst();
  }
}
