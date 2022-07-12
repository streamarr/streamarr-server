package com.streamarr.server.repositories.movie;

import com.streamarr.server.domain.media.MovieFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MovieFileRepository extends JpaRepository<MovieFile, UUID> {

    Optional<MovieFile> findFirstByFilepath(String filepath);
}
