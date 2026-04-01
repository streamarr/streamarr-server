package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Episode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EpisodeRepository extends JpaRepository<Episode, UUID> {

  List<Episode> findBySeasonId(UUID seasonId);

  List<Episode> findBySeasonIdIn(Collection<UUID> seasonIds);

  Optional<Episode> findBySeasonIdAndEpisodeNumber(UUID seasonId, int episodeNumber);
}
