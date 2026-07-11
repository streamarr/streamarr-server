package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Genre;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GenreRepository extends JpaRepository<Genre, UUID>, GenreRepositoryCustom {

  Optional<Genre> findBySourceId(String sourceId);

  @Query("SELECT g FROM Movie m JOIN m.genres g WHERE m.id = :movieId")
  List<Genre> findByMovieId(@Param("movieId") UUID movieId);

  @Query("SELECT g FROM Series s JOIN s.genres g WHERE s.id = :seriesId")
  List<Genre> findBySeriesId(@Param("seriesId") UUID seriesId);
}
