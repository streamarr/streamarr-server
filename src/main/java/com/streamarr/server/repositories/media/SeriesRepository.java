package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Series;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeriesRepository extends JpaRepository<Series, UUID>, SeriesRepositoryCustom {

  List<Series> findByLibrary_Id(UUID libraryId);
}
