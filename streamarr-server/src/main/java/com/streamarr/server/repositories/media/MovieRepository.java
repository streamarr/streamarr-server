package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MovieRepository extends JpaRepository<Movie, UUID>, MovieRepositoryCustom {

  List<Movie> findByLibrary_Id(UUID libraryId);

  @EntityGraph(attributePaths = "externalIds")
  @Query("SELECT m FROM Movie m WHERE m.library.id = :libraryId")
  List<Movie> findByLibraryIdWithExternalIds(@Param("libraryId") UUID libraryId);
}
