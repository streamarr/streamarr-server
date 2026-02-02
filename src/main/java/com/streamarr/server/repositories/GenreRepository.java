package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Genre;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GenreRepository extends JpaRepository<Genre, UUID> {

  Optional<Genre> findBySourceId(String sourceId);
}
