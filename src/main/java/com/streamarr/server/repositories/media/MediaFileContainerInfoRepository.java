package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

public interface MediaFileContainerInfoRepository
    extends Repository<MediaFileContainerInfo, UUID>, MediaFileContainerInfoRepositoryCustom {

  @EntityGraph(attributePaths = "streams")
  Optional<MediaFileContainerInfo> findByMediaFileId(UUID mediaFileId);
}
