package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createSolidPngImage;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fakes.TestImages.createTestImageWithMismatchedColorProfile;
import static com.streamarr.server.fakes.TestImages.createTransparentPngImage;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
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
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("UnitTest")
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
  @DisplayName("Should map ambient colors onto small image row when processing image")
  void shouldMapAmbientColorsOntoSmallImageRowWhenProcessingImage() {
    var entityId = UUID.randomUUID();
    var imageData = createSolidPngImage(600, 900, 0x00A0A0);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images());

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    var small =
        images.stream().filter(i -> i.getVariant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.getAmbientColors())
        .hasValueSatisfying(colors -> assertThat(colors.primary()).isEqualTo("#00a0a0"));
  }

  @Test
  @DisplayName("Should omit ambient colors when small image has insufficient opaque coverage")
  void shouldOmitAmbientColorsWhenSmallImageHasInsufficientOpaqueCoverage() {
    var result =
        imageService.processImage(
            createTransparentPngImage(),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE);

    assertThat(result.images())
        .filteredOn(image -> image.getVariant() == ImageSize.SMALL)
        .singleElement()
        .satisfies(image -> assertThat(image.getAmbientColors()).isEmpty());
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
  @DisplayName("Should process JPEG when embedded color profile does not match raster")
  void shouldProcessJpegWhenEmbeddedColorProfileDoesNotMatchRaster() {
    var result =
        imageService.processImage(
            createTestImageWithMismatchedColorProfile(),
            ImageType.PROFILE,
            UUID.randomUUID(),
            ImageEntityType.PERSON);

    assertThat(result.images())
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

    assertThat(result.writtenFiles()).hasSize(4).allSatisfy(path -> assertThat(path).exists());
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

    assertThat(smallImage.getPath())
        .isEqualTo("movie/" + entityId + "/poster/small-" + smallImage.getId() + ".jpg");
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

    assertThat(result.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
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
  @DisplayName("Should delete staged files when replacement contains no images")
  void shouldDeleteStagedFilesWhenReplacementContainsNoImages() throws IOException {
    var stagedFile = fileSystem.getPath("/data/images/staged.jpg");
    Files.createDirectories(stagedFile.getParent());
    Files.write(stagedFile, new byte[] {1, 2, 3});
    var emptyReplacement = new ImageService.ProcessedImage(List.of(), List.of(stagedFile));

    imageService.replaceImages(emptyReplacement);

    assertThat(stagedFile).doesNotExist();
  }

  @Test
  @DisplayName("Should delete files when concurrent image save loses conflict")
  void shouldDeleteFilesWhenConcurrentImageSaveLosesConflict() {
    var entityId = UUID.randomUUID();
    var firstResult =
        imageService.processImage(
            createTestImage(600, 900), ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(firstResult.images());

    var losingResult =
        imageService.processImage(
            createTestImage(600, 600), ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(losingResult.images());

    assertThat(losingResult.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
    assertThat(firstResult.writtenFiles()).allSatisfy(path -> assertThat(path).exists());
  }

  @Test
  @DisplayName(
      "Should atomically replace logical artwork and delete superseded files when artwork changes")
  void shouldAtomicallyReplaceLogicalArtworkAndDeleteSupersededFilesWhenArtworkChanges()
      throws IOException {
    var entityId = UUID.randomUUID();
    var existingArtwork = persistExistingArtwork(entityId);
    var replacement =
        imageService.processImage(
            createSolidPngImage(600, 900, 0x00A0A0),
            ImageType.POSTER,
            entityId,
            ImageEntityType.MOVIE,
            "/new-poster.jpg");

    imageService.replaceImages(replacement);

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .hasSize(4)
        .allSatisfy(image -> assertThat(image.getKey()).isEqualTo("/new-poster.jpg"))
        .extracting(Image::getId)
        .doesNotContain(existingArtwork.imageId());
    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).exists());
    assertThat(existingArtwork.absolutePath()).doesNotExist();
  }

  @Test
  @DisplayName("Should preserve existing artwork when atomic replacement fails")
  void shouldPreserveExistingArtworkWhenAtomicReplacementFails() throws IOException {
    var entityId = UUID.randomUUID();
    var existingArtwork = persistExistingArtwork(entityId);
    var replacement =
        imageService.processImage(
            createSolidPngImage(600, 900, 0x00A0A0),
            ImageType.POSTER,
            entityId,
            ImageEntityType.MOVIE,
            "/new-poster.jpg");
    imageRepository.setFailOnReplaceLogicalArtwork(true);

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Simulated logical artwork replacement failure");

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .singleElement()
        .satisfies(
            image -> {
              assertThat(image.getId()).isEqualTo(existingArtwork.imageId());
              assertThat(image.getKey()).isEqualTo("/old-poster.jpg");
            });
    assertThat(existingArtwork.absolutePath()).exists();
    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should defer staged file cleanup until rollback when replacement persistence fails")
  void shouldDeferStagedFileCleanupUntilRollbackWhenReplacementPersistenceFails() {
    var replacement =
        imageService.processImage(
            createSolidPngImage(600, 900, 0x00A0A0),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/new-poster.jpg");
    imageRepository.setFailOnReplaceLogicalArtwork(true);
    TransactionSynchronizationManager.initSynchronization();

    try {
      assertThatThrownBy(() -> imageService.replaceImages(replacement))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Simulated logical artwork replacement failure");
      assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).exists());

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

      assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      imageService.deleteFiles(replacement.writtenFiles());
    }
  }

  @Test
  @DisplayName("Should reject logical artwork replacement when content SHA-256 is invalid")
  void shouldRejectLogicalArtworkReplacementWhenContentSha256IsInvalid() throws IOException {
    var entityId = UUID.randomUUID();
    var oldImage =
        imageRepository.save(
            imageBuilder(entityId)
                .key("/old-poster.jpg")
                .path("movie/" + entityId + "/poster/small-old.jpg")
                .build());
    var stagedFile = fileSystem.getPath("/data/images/staged.jpg");
    Files.createDirectories(stagedFile.getParent());
    Files.write(stagedFile, new byte[] {1, 2, 3});
    var invalidReplacement =
        new ImageService.ProcessedImage(
            List.of(
                imageBuilder(entityId)
                    .key("/new-poster.jpg")
                    .contentSha256("not-a-sha256")
                    .path("movie/" + entityId + "/poster/small-new.jpg")
                    .build()),
            List.of(stagedFile));

    assertThatThrownBy(() -> imageService.replaceImages(invalidReplacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement contentSha256 must be 64 lowercase hexadecimal characters");

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .containsExactly(oldImage);
    assertThat(stagedFile).doesNotExist();
  }

  @Test
  @DisplayName("Should reject logical artwork replacement when content SHA-256 is null")
  void shouldRejectLogicalArtworkReplacementWhenContentSha256IsNull() {
    var replacement =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    replacement.images().getLast().setContentSha256(null);

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement contentSha256 must be 64 lowercase hexadecimal characters");

    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject replacement when variant content hashes differ")
  void shouldRejectReplacementWhenVariantContentHashesDiffer() {
    var replacement =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    replacement.images().getLast().setContentSha256("b".repeat(64));

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement variants must have the same contentSha256");

    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject replacement when variants span multiple logical artworks")
  void shouldRejectReplacementWhenVariantsSpanMultipleLogicalArtworks() {
    var replacement =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    replacement.images().getLast().setEntityId(UUID.randomUUID());

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement variants must describe one logical artwork");

    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject replacement when variant source keys differ")
  void shouldRejectReplacementWhenVariantSourceKeysDiffer() {
    var replacement =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    replacement.images().getLast().setKey("/different-poster.jpg");

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement variants must describe one logical artwork");

    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject replacement when variant entity types differ")
  void shouldRejectReplacementWhenVariantEntityTypesDiffer() {
    var replacement =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    replacement.images().getLast().setEntityType(ImageEntityType.SERIES);

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement variants must describe one logical artwork");

    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject replacement when variant image types differ")
  void shouldRejectReplacementWhenVariantImageTypesDiffer() {
    var replacement =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    replacement.images().getLast().setImageType(ImageType.BACKDROP);

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement variants must describe one logical artwork");

    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject replacement when generated variant set is incomplete")
  void shouldRejectReplacementWhenGeneratedVariantSetIsIncomplete() {
    var processed =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    var incompleteReplacement =
        new ImageService.ProcessedImage(
            processed.images().stream().limit(3).toList(), processed.writtenFiles());

    assertThatThrownBy(() -> imageService.replaceImages(incompleteReplacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement must contain exactly one of every image variant");

    assertThat(processed.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject replacement when generated variant set contains duplicate")
  void shouldRejectReplacementWhenGeneratedVariantSetContainsDuplicate() {
    var replacement =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    replacement.images().getLast().setVariant(replacement.images().getFirst().getVariant());

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Replacement must contain exactly one of every image variant");

    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  private ExistingArtwork persistExistingArtwork(UUID entityId) throws IOException {
    var imageId = UUID.randomUUID();
    var relativePath = "movie/" + entityId + "/poster/small-" + imageId + ".jpg";
    var absolutePath = fileSystem.getPath("/data/images").resolve(relativePath);
    Files.createDirectories(absolutePath.getParent());
    Files.write(absolutePath, new byte[] {1, 2, 3});
    imageRepository.save(
        imageBuilder(entityId).id(imageId).key("/old-poster.jpg").path(relativePath).build());
    return new ExistingArtwork(imageId, absolutePath);
  }

  private record ExistingArtwork(UUID imageId, Path absolutePath) {}

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
