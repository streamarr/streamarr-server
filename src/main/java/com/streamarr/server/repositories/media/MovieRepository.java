package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MovieRepository extends JpaRepository<Movie, UUID>, MovieRepositoryCustom {

  List<Movie> findByLibrary_Id(UUID libraryId);
}
