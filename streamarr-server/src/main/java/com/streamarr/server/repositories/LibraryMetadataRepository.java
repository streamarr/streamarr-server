package com.streamarr.server.repositories;

import com.streamarr.server.domain.LibraryMetadata;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LibraryMetadataRepository extends JpaRepository<LibraryMetadata, UUID> {

  List<LibraryMetadata> findByLibraryIdOrderByLetterAsc(UUID libraryId);

  void deleteByLibraryId(UUID libraryId);
}
