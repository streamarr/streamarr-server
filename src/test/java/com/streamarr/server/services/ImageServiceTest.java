package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.OPAQUE_TEAL;
import static com.streamarr.server.fakes.TestImages.alphaOfCornerPixel;
import static com.streamarr.server.fakes.TestImages.centrePixelOf;
import static com.streamarr.server.fakes.TestImages.createGifImage;
import static com.streamarr.server.fakes.TestImages.createOpaquePngImageWithAlphaChannel;
import static com.streamarr.server.fakes.TestImages.createPalettePngImageWithTransparentBorder;
import static com.streamarr.server.fakes.TestImages.createPngImageWithOneTranslucentPixel;
import static com.streamarr.server.fakes.TestImages.createPngImageWithTransparentBorder;
import static com.streamarr.server.fakes.TestImages.createSixteenBitGrayscalePngImage;
import static com.streamarr.server.fakes.TestImages.createSixteenBitOpaquePngImage;
import static com.streamarr.server.fakes.TestImages.createSixteenBitOpaquePngImageWithAlphaChannel;
import static com.streamarr.server.fakes.TestImages.createSixteenBitPngImageWithOneAlphaSampleBelowOpaque;
import static com.streamarr.server.fakes.TestImages.createSolidPngImage;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fakes.TestImages.createTestImageWithMismatchedColorProfile;
import static com.streamarr.server.fakes.TestImages.createTransparentPngImage;
import static com.streamarr.server.fakes.TestImages.formatNameOf;
import static com.streamarr.server.fixtures.ArtworkStepsAttemptFixture.attemptFor;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Named.named;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.exceptions.ImageStorageException;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fixtures.AmbientArtworkFixture;
import com.streamarr.server.services.metadata.ImageVariantService;
import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("UnitTest")
@DisplayName("Image Service Tests")
class ImageServiceTest {

  private static final Instant ATTEMPTED_AT = Instant.parse("2026-09-23T10:00:00Z");

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
        new ImageService(
            imageRepository,
            imageVariantService,
            imageProperties,
            fileSystem,
            new FakeItemResultRepository(),
            _ -> {});
  }

  @Test
  @DisplayName("Should map ambient colors onto small image row when processing image")
  void shouldMapAmbientColorsOntoSmallImageRowWhenProcessingImage() {
    var entityId = UUID.randomUUID();
    var imageData = createSolidPngImage(600, 900, 0x00A0A0);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images(), attemptFor(result.images()));

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    var small =
        images.stream().filter(i -> i.getVariant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.getAmbientColors())
        .hasValueSatisfying(colors -> assertThat(colors.primary()).isEqualTo("#00a0a0"));
  }

  @Test
  @DisplayName("Should return every artwork target when processing distinct palette profiles")
  void shouldReturnEveryArtworkTargetWhenProcessingDistinctPaletteProfiles() throws IOException {
    var result =
        imageService.processImage(
            AmbientArtworkFixture.profiles(),
            ImageType.BACKDROP,
            UUID.randomUUID(),
            ImageEntityType.MOVIE);

    var small =
        result.images().stream()
            .filter(image -> image.getVariant() == ImageSize.SMALL)
            .findFirst()
            .orElseThrow();
    var colors = small.getAmbientColors().orElseThrow();
    assertThat(colors.primary()).isEqualTo("#00a0a0");
    assertThat(colors.darkVibrant()).as("dark vibrant").isEqualTo("#103070");
    assertThat(colors.darkMuted()).as("dark muted").isEqualTo("#283830");
    assertThat(colors.lightVibrant()).as("light vibrant").isEqualTo("#68f8f8");
    assertThat(colors.lightMuted()).as("light muted").isEqualTo("#c8d0c8");
  }

  @ParameterizedTest(name = "overlapping primary RGB {0}")
  @ValueSource(ints = {0x00A0A0, 0x68F8F8})
  @DisplayName("Should reserve the swatch for primary when artwork fits another vibrant target")
  void shouldReserveSwatchForPrimaryWhenArtworkFitsAnotherVibrantTarget(int rgb)
      throws IOException {
    var result =
        imageService.processImage(
            AmbientArtworkFixture.solid().rgb(rgb).png(),
            ImageType.BACKDROP,
            UUID.randomUUID(),
            ImageEntityType.MOVIE);

    var small =
        result.images().stream()
            .filter(image -> image.getVariant() == ImageSize.SMALL)
            .findFirst()
            .orElseThrow();
    var colors = small.getAmbientColors().orElseThrow();
    assertThat(colors.primary()).isEqualTo(String.format("#%06x", rgb));
    assertThat(colors.darkVibrant()).isNull();
    assertThat(colors.darkMuted()).isNull();
    assertThat(colors.lightVibrant()).isNull();
    assertThat(colors.lightMuted()).isNull();
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
  @DisplayName("Should store resized variants as transparent PNG when image has transparent pixels")
  void shouldStoreResizedVariantsAsTransparentPngWhenImageHasTransparentPixels() {
    var result = processLogo(createPngImageWithTransparentBorder());

    assertThat(resizedVariantFilesOf(result))
        .hasSize(3)
        .allSatisfy(ImageServiceTest::assertPngWithTransparentBorder);
  }

  @Test
  @DisplayName(
      "Should store resized variants as transparent PNG when transparency is a palette entry")
  void shouldStoreResizedVariantsAsTransparentPngWhenTransparencyIsPaletteEntry() {
    var result = processLogo(createPalettePngImageWithTransparentBorder());

    assertThat(resizedVariantFilesOf(result))
        .hasSize(3)
        .allSatisfy(ImageServiceTest::assertPngWithTransparentBorder);
  }

  @Test
  @DisplayName("Should store resized variants as PNG when a single pixel is not fully opaque")
  void shouldStoreResizedVariantsAsPngWhenSinglePixelIsNotFullyOpaque() {
    var result = processPoster(createPngImageWithOneTranslucentPixel());

    assertThat(resizedVariantFilesOf(result))
        .hasSize(3)
        .allSatisfy(imageData -> assertThat(formatNameOf(imageData)).isEqualTo("png"));
  }

  @Test
  @DisplayName("Should store resized variants as PNG when one 16-bit alpha sample is below opaque")
  void shouldStoreResizedVariantsAsPngWhenOneSixteenBitAlphaSampleIsBelowOpaque() {
    var result = processPoster(createSixteenBitPngImageWithOneAlphaSampleBelowOpaque());

    assertThat(resizedVariantFilesOf(result))
        .hasSize(3)
        .allSatisfy(imageData -> assertThat(formatNameOf(imageData)).isEqualTo("png"));
  }

  @Test
  @DisplayName("Should omit BlurHash when image has transparent pixels")
  void shouldOmitBlurHashWhenImageHasTransparentPixels() {
    var result = processLogo(createPngImageWithTransparentBorder());

    assertThat(result.images())
        .hasSize(4)
        .allSatisfy(image -> assertThat(image.getBlurHash()).isNull());
  }

  @Test
  @DisplayName("Should omit ambient colors when image has transparent pixels")
  void shouldOmitAmbientColorsWhenImageHasTransparentPixels() {
    var result = processLogo(createPngImageWithTransparentBorder());

    assertThat(result.images())
        .hasSize(4)
        .allSatisfy(image -> assertThat(image.getAmbientColors()).isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("opaqueImagesThatResizeWithAlphaChannel")
  @DisplayName("Should store resized variants as JPEG when every pixel is opaque")
  void shouldStoreResizedVariantsAsJpegWhenEveryPixelIsOpaque(byte[] imageData) {
    var result = processPoster(imageData);

    assertThat(resizedVariantFilesOf(result))
        .hasSize(3)
        .allSatisfy(ImageServiceTest::assertJpegOfSolidTeal);
  }

  private static Stream<Named<byte[]>> opaqueImagesThatResizeWithAlphaChannel() {
    return Stream.of(
        named(
            "alpha channel in which every pixel is opaque", createOpaquePngImageWithAlphaChannel()),
        named("16-bit color samples without an alpha channel", createSixteenBitOpaquePngImage()),
        named(
            "16-bit color samples with a fully opaque alpha channel",
            createSixteenBitOpaquePngImageWithAlphaChannel()));
  }

  @Test
  @DisplayName(
      "Should compute the BlurHash of the picture without alpha when alpha channel is fully opaque")
  void shouldComputeBlurHashOfPictureWithoutAlphaWhenAlphaChannelIsFullyOpaque() {
    var withoutAlphaChannel = processPoster(createSolidPngImage(600, 900, 0x00A0A0));

    var withOpaqueAlphaChannel = processPoster(createOpaquePngImageWithAlphaChannel());

    assertThat(smallOf(withOpaqueAlphaChannel).getBlurHash())
        .isNotBlank()
        .isEqualTo(smallOf(withoutAlphaChannel).getBlurHash());
  }

  @Test
  @DisplayName("Should compute ambient colors when alpha channel is fully opaque")
  void shouldComputeAmbientColorsWhenAlphaChannelIsFullyOpaque() {
    var result = processPoster(createOpaquePngImageWithAlphaChannel());

    assertThat(smallOf(result).getAmbientColors())
        .hasValueSatisfying(colors -> assertThat(colors.primary()).isEqualTo("#00a0a0"));
  }

  @Test
  @DisplayName("Should fail without storing files when no encoder accepts a resized variant")
  void shouldFailWithoutStoringFilesWhenNoEncoderAcceptsResizedVariant() {
    var imageData = createSixteenBitGrayscalePngImage();

    assertThatThrownBy(() -> processPoster(imageData)).isInstanceOf(ImageProcessingException.class);

    assertThat(fileSystem.getPath("/data/images")).doesNotExist();
  }

  @Test
  @DisplayName("Should name every variant file as PNG when image has transparent pixels")
  void shouldNameEveryVariantFileAsPngWhenImageHasTransparentPixels() {
    var result = processLogo(createPngImageWithTransparentBorder());

    assertThat(result.images())
        .hasSize(4)
        .allSatisfy(image -> assertThat(image.getPath()).endsWith("-" + image.getId() + ".png"));
  }

  @Test
  @DisplayName("Should name the original file by its own format when resized variants are JPEG")
  void shouldNameOriginalFileByItsOwnFormatWhenResizedVariantsAreJpeg() {
    var result = processPoster(createSolidPngImage(600, 900, 0x00A0A0));

    assertThat(result.images())
        .extracting(image -> image.getVariant() + extensionOf(image))
        .containsExactlyInAnyOrder("SMALL.jpg", "MEDIUM.jpg", "LARGE.jpg", "ORIGINAL.png");
  }

  @Test
  @DisplayName(
      "Should name the original file without an extension when its format is neither JPEG nor PNG")
  void shouldNameOriginalFileWithoutExtensionWhenItsFormatIsNeitherJpegNorPng() {
    var result = processPoster(createGifImage());

    assertThat(result.images())
        .extracting(image -> image.getVariant() + extensionOf(image))
        .containsExactlyInAnyOrder("SMALL.jpg", "MEDIUM.jpg", "LARGE.jpg", "ORIGINAL");
  }

  @Test
  @DisplayName("Should persist all variant sizes when processing image")
  void shouldPersistAllVariantSizesWhenProcessingImage() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images(), attemptFor(result.images()));

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
    imageService.saveImages(result.images(), attemptFor(result.images()));

    assertThat(result.writtenFiles()).hasSize(4).allSatisfy(path -> assertThat(path).exists());
  }

  @Test
  @DisplayName("Should store relative path on image rows when saving")
  void shouldStoreRelativePathOnImageRowsWhenSaving() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(result.images(), attemptFor(result.images()));

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
  @DisplayName("Should skip duplicate images when saving same images twice")
  void shouldSkipDuplicateImagesWhenSaving() {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);

    var result =
        imageService.processImage(imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE);

    imageService.saveImages(result.images(), attemptFor(result.images()));
    imageService.saveImages(result.images(), attemptFor(result.images()));

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

    imageService.replaceImages(emptyReplacement, attemptFor(emptyReplacement.images()));

    assertThat(stagedFile).doesNotExist();
  }

  @Test
  @DisplayName("Should delete files when concurrent image save loses conflict")
  void shouldDeleteFilesWhenConcurrentImageSaveLosesConflict() {
    var entityId = UUID.randomUUID();
    var firstResult =
        imageService.processImage(
            createTestImage(600, 900), ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(firstResult.images(), attemptFor(firstResult.images()));

    var losingResult =
        imageService.processImage(
            createTestImage(600, 600), ImageType.POSTER, entityId, ImageEntityType.MOVIE);
    imageService.saveImages(losingResult.images(), attemptFor(losingResult.images()));

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

    imageService.replaceImages(replacement, attemptFor(replacement.images()));

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .hasSize(4)
        .allSatisfy(image -> assertThat(image.getKey()).isEqualTo("/new-poster.jpg"))
        .extracting(Image::getId)
        .doesNotContain(existingArtwork.imageId());
    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).exists());
    assertThat(existingArtwork.absolutePath()).doesNotExist();
  }

  @Test
  @DisplayName("Should delete superseded files only after commit when synchronization is active")
  void shouldDeleteSupersededFilesOnlyAfterCommitWhenSynchronizationIsActive() throws IOException {
    var entityId = UUID.randomUUID();
    var existingArtwork = persistExistingArtwork(entityId);
    var replacement =
        imageService.processImage(
            createSolidPngImage(600, 900, 0x00A0A0),
            ImageType.POSTER,
            entityId,
            ImageEntityType.MOVIE,
            "/new-poster.jpg");
    TransactionSynchronizationManager.initSynchronization();

    try {
      imageService.replaceImages(replacement, attemptFor(replacement.images()));

      assertThat(existingArtwork.absolutePath()).exists();

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);

      assertThat(existingArtwork.absolutePath()).doesNotExist();
      assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).exists());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      imageService.deleteFiles(replacement.writtenFiles());
    }
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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
      var attempt = attemptFor(replacement.images());
      assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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

    var attempt = attemptFor(invalidReplacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(invalidReplacement, attempt))
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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

    var attempt = attemptFor(incompleteReplacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(incompleteReplacement, attempt))
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

    var attempt = attemptFor(replacement.images());
    assertThatThrownBy(() -> imageService.replaceImages(replacement, attempt))
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
  @DisplayName("Should throw ImageStorageException when file write fails")
  void shouldThrowImageStorageExceptionWhenFileWriteFails() throws IOException {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);
    fileSystem.close();

    assertThatThrownBy(
            () ->
                imageService.processImage(
                    imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE))
        .isInstanceOf(ImageStorageException.class);
  }

  private ImageService.ProcessedImage processLogo(byte[] imageData) {
    return imageService.processImage(
        imageData, ImageType.LOGO, UUID.randomUUID(), ImageEntityType.COMPANY);
  }

  private ImageService.ProcessedImage processPoster(byte[] imageData) {
    return imageService.processImage(
        imageData, ImageType.POSTER, UUID.randomUUID(), ImageEntityType.MOVIE);
  }

  private List<byte[]> resizedVariantFilesOf(ImageService.ProcessedImage processed) {
    return processed.images().stream()
        .filter(image -> image.getVariant() != ImageSize.ORIGINAL)
        .map(this::storedBytesOf)
        .toList();
  }

  private byte[] storedBytesOf(Image image) {
    try {
      return imageService.readImageFile(image);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Image smallOf(ImageService.ProcessedImage processed) {
    return processed.images().stream()
        .filter(image -> image.getVariant() == ImageSize.SMALL)
        .findFirst()
        .orElseThrow();
  }

  // The part of the stored file's name after the image id.
  private static String extensionOf(Image image) {
    var imageId = image.getId().toString();
    return image.getPath().substring(image.getPath().indexOf(imageId) + imageId.length());
  }

  private static void assertPngWithTransparentBorder(byte[] imageData) {
    assertThat(formatNameOf(imageData)).isEqualTo("png");
    assertThat(alphaOfCornerPixel(imageData)).as("corner alpha").isZero();
    assertThat(centrePixelOf(imageData)).as("centre pixel").isEqualTo(OPAQUE_TEAL);
  }

  private static void assertJpegOfSolidTeal(byte[] imageData) {
    assertThat(formatNameOf(imageData)).isEqualTo("jpeg");
    var centre = new Color(centrePixelOf(imageData));
    assertThat(centre.getRed()).as("red").isCloseTo(0x00, within(4));
    assertThat(centre.getGreen()).as("green").isCloseTo(0xA0, within(4));
    assertThat(centre.getBlue()).as("blue").isCloseTo(0xA0, within(4));
  }
}
