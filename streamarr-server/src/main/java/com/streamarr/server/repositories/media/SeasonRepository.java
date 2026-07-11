package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Season;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeasonRepository extends JpaRepository<Season, UUID>, SeasonRepositoryCustom {

  Optional<Season> findBySeriesIdAndSeasonNumber(UUID seriesId, int seasonNumber);

  List<Season> findBySeriesIdOrderBySeasonNumber(UUID seriesId);
}
