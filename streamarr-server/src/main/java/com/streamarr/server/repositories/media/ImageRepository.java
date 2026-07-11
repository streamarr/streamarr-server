package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImageRepository extends JpaRepository<Image, UUID>, ImageRepositoryCustom {

  List<Image> findByEntityIdAndEntityType(UUID entityId, ImageEntityType entityType);

  List<Image> findByEntityIdAndEntityTypeAndImageType(
      UUID entityId, ImageEntityType entityType, ImageType imageType);

  List<Image> findByEntityTypeAndEntityIdIn(ImageEntityType entityType, Collection<UUID> entityIds);

  List<Image> findByEntityId(UUID entityId);

  void deleteByEntityIdAndEntityType(UUID entityId, ImageEntityType entityType);
}
