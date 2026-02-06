package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Season;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeasonRepository extends JpaRepository<Season, UUID> {

  Optional<Season> findBySeriesIdAndSeasonNumber(UUID seriesId, int seasonNumber);

  List<Season> findBySeriesId(UUID seriesId);
}
