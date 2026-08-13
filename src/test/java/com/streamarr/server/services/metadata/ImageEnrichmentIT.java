package com.streamarr.server.services.metadata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.fakes.TestImages.createDistinctColorPngImage;
import static com.streamarr.server.fakes.TestImages.createSolidPngImage;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fakes.TestImages.createTransparentPngImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Image Enrichment Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageEnrichmentIT extends AbstractWireMockIntegrationTest {

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ImageService imageService;

  @BeforeEach
  void resetStubs() {
    wireMock.resetAll();
  }

  @Test
  @DisplayName("Should persist images with correct type when event published within a transaction")
  void shouldPersistImagesWithCorrectTypeWhenEventPublishedWithinTransaction() {
    var entityId = UUID.randomUUID();
    stubImageDownload("/poster.jpg");

    transactionTemplate.executeWithoutResult(
        status ->
            eventPublisher.publishEvent(
                new MetadataEnrichedEvent(
                    entityId,
                    ImageEntityType.MOVIE,
                    List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images).extracting(Image::getImageType).containsOnly(ImageType.POSTER);
            });
  }

  @Test
  @DisplayName("Should persist ambient colors on small variant when enrichment completes")
  void shouldPersistAmbientColorsOnSmallVariantWhenEnrichmentCompletes() {
    var entityId = UUID.randomUUID();
    stubImageDownload("/backdrop.jpg", createDistinctColorPngImage());

    transactionTemplate.executeWithoutResult(
        status ->
            eventPublisher.publishEvent(
                new MetadataEnrichedEvent(
                    entityId,
                    ImageEntityType.MOVIE,
                    List.of(new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg")))));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images)
                  .filteredOn(image -> image.getVariant() == ImageSize.SMALL)
                  .singleElement()
                  .satisfies(
                      small ->
                          assertThat(small.getAmbientColors())
                              .hasValue(
                                  AmbientColors.builder()
                                      .topLeft("#202020")
                                      .topRight("#404040")
                                      .bottomRight("#c0c0c0")
                                      .bottomLeft("#808080")
                                      .primary("#00a0a0")
                                      .build()));
            });
  }

  @Test
  @DisplayName("Should persist null ambient colors when artwork has insufficient opaque coverage")
  void shouldPersistNullAmbientColorsWhenArtworkHasInsufficientOpaqueCoverage() {
    var entityId = UUID.randomUUID();
    stubImageDownload("/transparent.png", createTransparentPngImage());

    transactionTemplate.executeWithoutResult(
        status ->
            eventPublisher.publishEvent(
                new MetadataEnrichedEvent(
                    entityId,
                    ImageEntityType.MOVIE,
                    List.of(new TmdbImageSource(ImageType.POSTER, "/transparent.png")))));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images)
                  .filteredOn(image -> image.getVariant() == ImageSize.SMALL)
                  .singleElement()
                  .satisfies(image -> assertThat(image.getAmbientColors()).isEmpty());
            });
  }

  @Test
  @DisplayName("Should persist source key and content SHA-256 when enrichment completes")
  void shouldPersistSourceKeyAndContentSha256WhenEnrichmentCompletes()
      throws NoSuchAlgorithmException {
    var entityId = UUID.randomUUID();
    var imageData = createTestImage(600, 900);
    var sourceKey = "/poster.jpg";
    var processed =
        imageService.processImage(
            imageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE, sourceKey);

    imageService.saveImages(processed.images());

    var expectedContentSha256 =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(imageData));
    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .hasSize(ImageSize.values().length)
        .allSatisfy(
            image -> {
              assertThat(image.getKey()).isEqualTo(sourceKey);
              assertThat(image.getContentSha256()).isEqualTo(expectedContentSha256);
            });
  }

  @Test
  @DisplayName("Should replace changed artwork and recompute derived metadata atomically")
  void shouldReplaceChangedArtworkAndRecomputeDerivedMetadataAtomically()
      throws NoSuchAlgorithmException {
    var entityId = UUID.randomUUID();
    var oldKey = "/old-poster.jpg";
    var newKey = "/new-poster.png";
    var original =
        imageService.processImage(
            createSolidPngImage(600, 900, 0x0000FF),
            ImageType.POSTER,
            entityId,
            ImageEntityType.MOVIE,
            oldKey);
    imageService.saveImages(original.images());
    var originalImages =
        imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    var originalIds = originalImages.stream().map(Image::getId).toList();
    var originalSmall =
        originalImages.stream()
            .filter(image -> image.getVariant() == ImageSize.SMALL)
            .findFirst()
            .orElseThrow();

    var newImageData = createSolidPngImage(600, 900, 0x00A0A0);
    var replacement =
        imageService.processImage(
            newImageData, ImageType.POSTER, entityId, ImageEntityType.MOVIE, newKey);

    imageService.replaceImages(replacement);

    var expectedContentSha256 =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(newImageData));
    var replacements = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(replacements)
        .hasSize(ImageSize.values().length)
        .allSatisfy(
            image -> {
              assertThat(image.getKey()).isEqualTo(newKey);
              assertThat(image.getContentSha256()).isEqualTo(expectedContentSha256);
            })
        .extracting(Image::getId)
        .doesNotContainAnyElementsOf(originalIds);
    assertThat(replacements)
        .filteredOn(image -> image.getVariant() == ImageSize.SMALL)
        .singleElement()
        .satisfies(
            newSmall -> {
              assertThat(newSmall.getBlurHash()).isNotEqualTo(originalSmall.getBlurHash());
              assertThat(newSmall.getAmbientColors())
                  .isNotEqualTo(originalSmall.getAmbientColors());
            });
    assertThatThrownBy(() -> imageService.readImageFile(originalSmall))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  @DisplayName("Should roll back database replacement and preserve files when insert fails")
  void shouldRollBackDatabaseReplacementAndPreserveFilesWhenInsertFails() {
    var entityId = UUID.randomUUID();
    var oldKey = "/rollback-old.jpg";
    var original =
        imageService.processImage(
            createTestImage(600, 900), ImageType.POSTER, entityId, ImageEntityType.MOVIE, oldKey);
    imageService.saveImages(original.images());
    var originalImages =
        imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    var originalIds = originalImages.stream().map(Image::getId).toList();
    var replacement =
        imageService.processImage(
            createSolidPngImage(600, 900, 0x00A0A0),
            ImageType.POSTER,
            entityId,
            ImageEntityType.MOVIE,
            "/rollback-new.png");
    replacement.images().getLast().setPath(null);

    assertThatThrownBy(() -> imageService.replaceImages(replacement))
        .isInstanceOf(RuntimeException.class);

    var preserved = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(preserved).extracting(Image::getId).containsExactlyInAnyOrderElementsOf(originalIds);
    assertThat(preserved)
        .allSatisfy(image -> assertThat(image.getKey()).isEqualTo(oldKey))
        .allSatisfy(image -> assertThat(imageService.readImageFile(image)).isNotEmpty());
    assertThat(replacement.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
  }

  @Test
  @DisplayName("Should reject malformed content SHA-256 at database boundary")
  void shouldRejectMalformedContentSha256AtDatabaseBoundary() {
    var processed =
        imageService.processImage(
            createTestImage(600, 900),
            ImageType.POSTER,
            UUID.randomUUID(),
            ImageEntityType.MOVIE,
            "/poster.jpg");
    var invalidImage = processed.images().getFirst();
    invalidImage.setContentSha256("not-a-sha256");

    try {
      assertThatThrownBy(() -> imageRepository.insertAllIfAbsent(List.of(invalidImage)))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("image_content_sha256_format_check");
    } finally {
      imageService.deleteFiles(processed.writtenFiles());
    }
  }

  private void stubImageDownload(String path) {
    stubImageDownload(path, createTestImage(600, 900));
  }

  private void stubImageDownload(String path, byte[] imageData) {
    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(imageData)));
  }
}
