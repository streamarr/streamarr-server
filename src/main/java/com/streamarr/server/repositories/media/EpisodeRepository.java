package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Episode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EpisodeRepository extends JpaRepository<Episode, UUID>, EpisodeRepositoryCustom {

  @Query(
      """
      select episode
      from Episode episode
      where episode.season.id = :seasonId
      order by episode.episodeNumber
      """)
  List<Episode> findBySeasonId(@Param("seasonId") UUID seasonId);

  List<Episode> findBySeasonIdIn(Collection<UUID> seasonIds);

  Optional<Episode> findBySeasonIdAndEpisodeNumber(UUID seasonId, int episodeNumber);
}
