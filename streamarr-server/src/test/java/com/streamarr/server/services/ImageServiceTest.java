package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.services.metadata.ImageVariantService;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Image Service Tests")
class ImageServiceTest {

  private FakeImageRepository imageRepository;
  private ImageService imageService;
  private FileSystem fileSystem;

  @BeforeEach
  void setUp() {
    imageRepository = new FakeImageRepository();
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageProperties = new ImageProperties("/data/images");
    var imageVariantService = new ImageVariantService();
    imageService =
        new ImageService(imageRepository, imageVariantService, imageProperties, fileSystem);
  }

  @Test
  @DisplayName("Should persist all variant sizes when processing image")
  void shouldPersistAllVariantSizesWhenProcessingImage() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images());

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(images)
        .extracting(Image::getVariant)
        .containsExactlyInAnyOrder(
            ImageSize.SMALL, ImageSize.MEDIUM, ImageSize.LARGE, ImageSize.ORIGINAL);
  }

  @Test
  @DisplayName("Should write variant files to disk when processing image")
  void shouldWriteVariantFilesToDiskWhenProcessingImage() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images());

    var basePath = fileSystem.getPath("/data/images/movie", entityId.toString(), "poster");
    assertThat(Files.exists(basePath.resolve("small.jpg"))).isTrue();
    assertThat(Files.exists(basePath.resolve("medium.jpg"))).isTrue();
    assertThat(Files.exists(basePath.resolve("large.jpg"))).isTrue();
    assertThat(Files.exists(basePath.resolve("original.jpg"))).isTrue();
  }

  @Test
  @DisplayName("Should store relative path on image rows when saving")
  void shouldStoreRelativePathOnImageRowsWhenSaving() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images());

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    var smallImage =
        images.stream().filter(i -> i.getVariant() == ImageSize.SMALL).findFirst().orElseThrow();

    assertThat(smallImage.getPath()).isEqualTo("movie/" + entityId + "/poster/small.jpg");
  }

  @Test
  @DisplayName("Should return image when found by ID")
  void shouldReturnImageWhenFoundById() {
    var image = imageRepository.save(Image.builder().path("test/path.jpg").build());

    var result = imageService.findById(image.getId());

    assertThat(result).isPresent();
    assertThat(result.get().getPath()).isEqualTo("test/path.jpg");
  }

  @Test
  @DisplayName("Should return empty when image not found by ID")
  void shouldReturnEmptyWhenImageNotFoundById() {
    var result = imageService.findById(UUID.randomUUID());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return file contents when reading image file")
  void shouldReturnFileContentsWhenReadingImageFile() throws IOException {
    var relativePath = "movie/test/poster/small.jpg";
    var absolutePath = fileSystem.getPath("/data/images").resolve(relativePath);
    Files.createDirectories(absolutePath.getParent());
    var content = new byte[] {1, 2, 3};
    Files.write(absolutePath, content);

    var image = Image.builder().path(relativePath).build();

    var result = imageService.readImageFile(image);

    assertThat(result).isEqualTo(content);
  }

  @Test
  @DisplayName("Should delete image rows and files when deleting for entity")
  void shouldDeleteImageRowsAndFilesWhenDeletingForEntity() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images());

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .isNotEmpty();

    imageService.deleteImagesForEntity(entityId, ImageEntityType.MOVIE);

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .isEmpty();

    var basePath = fileSystem.getPath("/data/images/movie", entityId.toString(), "poster");
    assertThat(Files.exists(basePath.resolve("small.jpg"))).isFalse();
  }

  @Test
  @DisplayName("Should not fail when deleting images for entity with no images")
  void shouldNotFailWhenDeletingImagesForEntityWithNoImages() {
    assertThatCode(
            () -> imageService.deleteImagesForEntity(UUID.randomUUID(), ImageEntityType.MOVIE))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should skip duplicate images when saving same images twice")
  void shouldSkipDuplicateImagesWhenSaving() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);

    imageService.saveImages(result.images());
    imageService.saveImages(result.images());

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(images)
        .hasSize(4)
        .extracting(Image::getVariant)
        .containsExactlyInAnyOrder(
            ImageSize.SMALL, ImageSize.MEDIUM, ImageSize.LARGE, ImageSize.ORIGINAL);
  }

  @Test
  @DisplayName("Should throw ImageProcessingException when file write fails")
  void shouldThrowImageProcessingExceptionWhenFileWriteFails() throws IOException {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);
    fileSystem.close();

    assertThatThrownBy(
            () ->
                imageService.processImage(
                    imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE))
        .isInstanceOf(ImageProcessingException.class);
  }
}
