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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

  private final ImageRepository imageRepository;
  private final ImageVariantService imageVariantService;
  private final ImageProperties imageProperties;
  private final FileSystem fileSystem;

  @Transactional
  public void processAndSaveImage(
      byte[] originalData, ImageType imageType, UUID entityId, ImageEntityType entityType) {
    var variants = imageVariantService.generateVariants(originalData, imageType);
    var writtenFiles = new ArrayList<Path>();

    try {
      var images = new ArrayList<Image>();

      for (var variant : variants) {
        var relativePath = buildRelativePath(entityType, entityId, imageType, variant.variant());
        var absolutePath = resolveAbsolutePath(relativePath);

        Files.createDirectories(absolutePath.getParent());
        Files.write(absolutePath, variant.data());
        writtenFiles.add(absolutePath);

        images.add(
            Image.builder()
                .entityId(entityId)
                .entityType(entityType)
                .imageType(imageType)
                .variant(variant.variant())
                .width(variant.width())
                .height(variant.height())
                .blurHash(variant.blurHash())
                .path(relativePath)
                .build());
      }

      imageRepository.saveAll(images);
    } catch (Exception e) {
      deleteFiles(writtenFiles);
      throw new ImageProcessingException("Failed to process and save image", e);
    }
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
      ImageEntityType entityType, UUID entityId, ImageType imageType, ImageSize variant) {
    return String.join(
        "/",
        entityType.name().toLowerCase(),
        entityId.toString(),
        imageType.name().toLowerCase(),
        variant.name().toLowerCase() + ".jpg");
  }

  private Path resolveAbsolutePath(String relativePath) {
    return fileSystem.getPath(imageProperties.storagePath()).resolve(relativePath);
  }

  private void deleteFiles(List<Path> files) {
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
