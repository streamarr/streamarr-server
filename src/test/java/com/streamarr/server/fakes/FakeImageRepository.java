package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.repositories.media.ImageRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeImageRepository extends FakeJpaRepository<Image> implements ImageRepository {

  private boolean failOnSaveAll;

  public void setFailOnSaveAll(boolean failOnSaveAll) {
    this.failOnSaveAll = failOnSaveAll;
  }

  @Override
  public <S extends Image> List<S> saveAll(Iterable<S> entities) {
    if (failOnSaveAll) {
      throw new RuntimeException("Simulated saveAll failure");
    }

    List<S> entityList = new ArrayList<>();
    entities.forEach(
        entity -> {
          if (isDuplicate(entity)) {
            throw new DataIntegrityViolationException(
                "Duplicate image: entityId="
                    + entity.getEntityId()
                    + ", imageType="
                    + entity.getImageType()
                    + ", variant="
                    + entity.getVariant());
          }
          entityList.add(save(entity));
        });
    return entityList;
  }

  private boolean isDuplicate(Image image) {
    return database.values().stream()
        .anyMatch(
            existing ->
                existing.getEntityId().equals(image.getEntityId())
                    && existing.getImageType() == image.getImageType()
                    && existing.getVariant() == image.getVariant());
  }

  @Override
  public List<Image> findByEntityIdAndEntityType(UUID entityId, ImageEntityType entityType) {
    return database.values().stream()
        .filter(image -> entityId.equals(image.getEntityId()))
        .filter(image -> entityType.equals(image.getEntityType()))
        .toList();
  }

  @Override
  public List<Image> findByEntityIdAndEntityTypeAndImageType(
      UUID entityId, ImageEntityType entityType, ImageType imageType) {
    return database.values().stream()
        .filter(image -> entityId.equals(image.getEntityId()))
        .filter(image -> entityType.equals(image.getEntityType()))
        .filter(image -> imageType.equals(image.getImageType()))
        .toList();
  }

  @Override
  public List<Image> findByEntityTypeAndEntityIdIn(
      ImageEntityType entityType, Collection<UUID> entityIds) {
    return database.values().stream()
        .filter(image -> entityType.equals(image.getEntityType()))
        .filter(image -> entityIds.contains(image.getEntityId()))
        .toList();
  }

  @Override
  public List<Image> findByEntityId(UUID entityId) {
    return database.values().stream()
        .filter(image -> entityId.equals(image.getEntityId()))
        .toList();
  }

  @Override
  public void deleteByEntityIdAndEntityType(UUID entityId, ImageEntityType entityType) {
    var toRemove =
        database.values().stream()
            .filter(image -> entityId.equals(image.getEntityId()))
            .filter(image -> entityType.equals(image.getEntityType()))
            .map(Image::getId)
            .toList();

    toRemove.forEach(database::remove);
  }
}
