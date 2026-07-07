package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Season;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SeasonRepository extends JpaRepository<Season, UUID>, SeasonRepositoryCustom {

  Optional<Season> findBySeriesIdAndSeasonNumber(UUID seriesId, int seasonNumber);

  @Query(
      """
      select season
      from Season season
      where season.series.id = :seriesId
      order by season.seasonNumber
      """)
  List<Season> findBySeriesId(@Param("seriesId") UUID seriesId);

  List<Season> findBySeriesIdIn(Collection<UUID> seriesIds);
}
