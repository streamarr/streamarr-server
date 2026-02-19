package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.repositories.media.EpisodeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeEpisodeRepository extends FakeJpaRepository<Episode> implements EpisodeRepository {

  @Override
  public List<Episode> findBySeasonId(UUID seasonId) {
    return database.values().stream()
        .filter(
            episode -> episode.getSeason() != null && seasonId.equals(episode.getSeason().getId()))
        .toList();
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
