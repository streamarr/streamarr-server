package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.MediaFile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

  Optional<MediaFile> findFirstByFilepath(String filepath);

  List<MediaFile> findByMediaId(UUID mediaId);

  List<MediaFile> findByLibraryId(UUID libraryId);

  @Query("SELECT DISTINCT m.mediaId FROM MediaFile m WHERE m.mediaId IN :mediaIds")
  Set<UUID> findDistinctMediaIdsByMediaIdIn(@Param("mediaIds") Collection<UUID> mediaIds);
}
