package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;


@Repository
public interface MovieRepository extends JpaRepository<Movie, UUID>, MovieRepositoryCustom {

    @Transactional
    Optional<Movie> findFirstByTmdbId(String tmdbId);

}
