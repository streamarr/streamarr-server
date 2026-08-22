package com.streamarr.server.services;

import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.metadata.ImageVariantService;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

  private static final Pattern CONTENT_SHA256 = Pattern.compile("[0-9a-f]{64}");

  private record ArtworkIdentity(
      UUID entityId, ImageEntityType entityType, ImageType imageType, String key) {}

  private final ImageRepository imageRepository;
  private final ImageVariantService imageVariantService;
  private final ImageProperties imageProperties;
  private final FileSystem fileSystem;

  public record ProcessedImage(List<Image> images, List<Path> writtenFiles) {}

  public ProcessedImage processImage(
      byte[] originalData, ImageType imageType, UUID entityId, ImageEntityType entityType) {
    return processImage(originalData, imageType, entityId, entityType, null);
  }

  public ProcessedImage processImage(
      byte[] originalData,
      ImageType imageType,
      UUID entityId,
      ImageEntityType entityType,
      String key) {
    var variants = imageVariantService.generateVariants(originalData, imageType);
    var contentSha256 = sha256(originalData);
    var writtenFiles = new ArrayList<Path>();

    try {
      var images = new ArrayList<Image>();

      for (var variant : variants) {
        var imageId = UUID.randomUUID();
        var relativePath =
            buildRelativePath(entityType, entityId, imageType, variant.variant(), imageId);
        var absolutePath = resolveAbsolutePath(relativePath);

        Files.createDirectories(absolutePath.getParent());
        Files.write(absolutePath, variant.data());
        writtenFiles.add(absolutePath);

        images.add(
            Image.builder()
                .id(imageId)
                .entityId(entityId)
                .entityType(entityType)
                .imageType(imageType)
                .variant(variant.variant())
                .width(variant.width())
                .height(variant.height())
                .blurHash(variant.blurHash())
                .ambientColors(variant.ambientColors())
                .key(key)
                .contentSha256(contentSha256)
                .path(relativePath)
                .build());
      }

      return new ProcessedImage(images, writtenFiles);
    } catch (Exception e) {
      deleteFiles(writtenFiles);
      throw new ImageProcessingException("Failed to process image", e);
    }
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void saveImages(List<Image> images) {
    var insertedImageIds = imageRepository.insertAllIfAbsent(images);

    for (var image : images) {
      if (insertedImageIds.contains(image.getId()) || imageRepository.existsById(image.getId())) {
        continue;
      }

      deleteFile(resolveAbsolutePath(image.getPath()));
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void replaceImages(ProcessedImage replacement) {
    if (replacement.images().isEmpty()) {
      deleteFiles(replacement.writtenFiles());
      return;
    }

    var cleanupDeferred = deferReplacementFileCleanupUntilRollback(replacement.writtenFiles());

    try {
      validateContentSha256(replacement.images());
      validateArtworkIdentity(replacement.images());
      validateVariantSet(replacement.images());
      var replacedPaths = imageRepository.replaceLogicalArtwork(replacement.images());
      var existingFiles = replacedPaths.stream().map(this::resolveAbsolutePath).toList();
      scheduleSupersededFileCleanup(existingFiles);
    } catch (RuntimeException e) {
      if (!cleanupDeferred) {
        deleteFiles(replacement.writtenFiles());
      }
      throw e;
    }
  }

  private void validateContentSha256(List<Image> images) {
    var invalidHash =
        images.stream()
            .map(Image::getContentSha256)
            .anyMatch(hash -> hash == null || !CONTENT_SHA256.matcher(hash).matches());
    if (invalidHash) {
      throw new IllegalArgumentException(
          "Replacement contentSha256 must be 64 lowercase hexadecimal characters");
    }

    var expectedHash = images.getFirst().getContentSha256();
    if (images.stream().anyMatch(image -> !expectedHash.equals(image.getContentSha256()))) {
      throw new IllegalArgumentException("Replacement variants must have the same contentSha256");
    }
  }

  private void validateArtworkIdentity(List<Image> images) {
    var identityCount =
        images.stream()
            .map(
                image ->
                    new ArtworkIdentity(
                        image.getEntityId(),
                        image.getEntityType(),
                        image.getImageType(),
                        image.getKey()))
            .distinct()
            .count();
    if (identityCount != 1) {
      throw new IllegalArgumentException("Replacement variants must describe one logical artwork");
    }
  }

  private void validateVariantSet(List<Image> images) {
    var variants = EnumSet.copyOf(images.stream().map(Image::getVariant).toList());
    if (images.size() != ImageSize.values().length
        || !variants.equals(EnumSet.allOf(ImageSize.class))) {
      throw new IllegalArgumentException(
          "Replacement must contain exactly one of every image variant");
    }
  }

  private boolean deferReplacementFileCleanupUntilRollback(List<Path> replacementFiles) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
              deleteFiles(replacementFiles);
            }
          }
        });
    return true;
  }

  private void scheduleSupersededFileCleanup(List<Path> existingFiles) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      deleteFiles(existingFiles);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            deleteFiles(existingFiles);
          }
        });
  }

  public Optional<Image> findById(UUID imageId) {
    return imageRepository.findById(imageId);
  }

  public List<Image> findByEntity(UUID entityId, ImageEntityType entityType) {
    return imageRepository.findByEntityIdAndEntityType(entityId, entityType);
  }

  public byte[] readImageFile(Image image) throws IOException {
    var absolutePath = resolveAbsolutePath(image.getPath());
    return Files.readAllBytes(absolutePath);
  }

  @Transactional
  public void deleteImagesForEntity(UUID entityId, ImageEntityType entityType) {
    var images = imageRepository.findByEntityIdAndEntityType(entityId, entityType);

    for (var image : images) {
      deleteFile(resolveAbsolutePath(image.getPath()));
    }

    imageRepository.deleteByEntityIdAndEntityType(entityId, entityType);
  }

  private String buildRelativePath(
      ImageEntityType entityType,
      UUID entityId,
      ImageType imageType,
      ImageSize variant,
      UUID imageId) {
    return String.join(
        "/",
        entityType.name().toLowerCase(),
        entityId.toString(),
        imageType.name().toLowerCase(),
        variant.name().toLowerCase() + "-" + imageId + ".jpg");
  }

  private Path resolveAbsolutePath(String relativePath) {
    return fileSystem.getPath(imageProperties.storagePath()).resolve(relativePath);
  }

  public void deleteFiles(List<Path> files) {
    for (var file : files) {
      deleteFile(file);
    }
  }

  private void deleteFile(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete image file: {}", file, e);
    }
  }
}
