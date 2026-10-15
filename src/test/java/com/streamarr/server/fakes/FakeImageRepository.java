package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ObsoleteArtworkAttemptException;
import com.streamarr.server.repositories.media.ImageRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

public class FakeImageRepository extends FakeJpaRepository<Image> implements ImageRepository {

  private boolean failOnInsertAllIfAbsent;
  private boolean failOnReplaceLogicalArtwork;
  private BiPredicate<ArtworkTarget, Long> currentArtworkGeneration = (_, _) -> true;

  /** Decides whether an attempt's generation is still the target's pending artwork request. */
  public void checkArtworkGenerationsWith(
      BiPredicate<ArtworkTarget, Long> currentArtworkGeneration) {
    this.currentArtworkGeneration = currentArtworkGeneration;
  }

  public void setFailOnInsertAllIfAbsent(boolean failOnInsertAllIfAbsent) {
    this.failOnInsertAllIfAbsent = failOnInsertAllIfAbsent;
  }

  public void setFailOnReplaceLogicalArtwork(boolean failOnReplaceLogicalArtwork) {
    this.failOnReplaceLogicalArtwork = failOnReplaceLogicalArtwork;
  }

  @Override
  public Set<UUID> insertAllIfAbsent(List<Image> images, ArtworkAttempt attempt) {
    if (failOnInsertAllIfAbsent) {
      throw new RuntimeException("Simulated insertAllIfAbsent failure");
    }

    requireCurrentGeneration(attempt);

    var insertedImageIds = new HashSet<UUID>();

    for (var image : images) {
      if (!isDuplicate(image)) {
        save(image);
        insertedImageIds.add(image.getId());
      }
    }

    return Set.copyOf(insertedImageIds);
  }

  @Override
  public List<String> replaceLogicalArtwork(List<Image> images, ArtworkAttempt attempt) {
    if (failOnReplaceLogicalArtwork) {
      throw new RuntimeException("Simulated logical artwork replacement failure");
    }

    requireCurrentGeneration(attempt);
    if (images.isEmpty()) {
      return List.of();
    }

    var first = images.getFirst();
    var existing =
        findByEntityIdAndEntityTypeAndImageType(
            first.getEntityId(), first.getEntityType(), first.getImageType());
    var replacedPaths = existing.stream().map(Image::getPath).toList();
    existing.forEach(image -> database.remove(image.getId()));
    saveAll(images);
    return replacedPaths;
  }

  private void requireCurrentGeneration(ArtworkAttempt attempt) {
    if (!currentArtworkGeneration.test(attempt.target(), attempt.generation())) {
      throw new ObsoleteArtworkAttemptException(attempt);
    }
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
  public List<String> lockMoviesAndFindArtworkPaths(Collection<UUID> movieIds) {
    return artworkPaths(ImageEntityType.MOVIE, movieIds);
  }

  // The fake knows no seasons or episodes, so it returns only the series' own artwork.
  @Override
  public List<String> lockSeriesAndFindArtworkPaths(Collection<UUID> seriesIds) {
    return artworkPaths(ImageEntityType.SERIES, seriesIds);
  }

  private List<String> artworkPaths(ImageEntityType entityType, Collection<UUID> entityIds) {
    return findByEntityTypeAndEntityIdIn(entityType, entityIds).stream()
        .map(Image::getPath)
        .toList();
  }
}
