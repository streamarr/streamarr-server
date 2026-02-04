package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.MediaFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

  Optional<MediaFile> findFirstByFilepath(String filepath);

  List<MediaFile> findByMediaId(UUID mediaId);

  List<MediaFile> findByLibraryId(UUID libraryId);
}
