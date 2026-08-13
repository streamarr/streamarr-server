package com.streamarr.server.services.metadata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
    stubImageDownload(sourceKey, imageData);

    transactionTemplate.executeWithoutResult(
        status ->
            eventPublisher.publishEvent(
                new MetadataEnrichedEvent(
                    entityId,
                    ImageEntityType.MOVIE,
                    List.of(new TmdbImageSource(ImageType.POSTER, sourceKey)))));

    var expectedContentSha256 =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(imageData));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images).isNotEmpty();
              assertThat(images)
                  .allSatisfy(
                      image -> {
                        assertThat(image.getKey()).isEqualTo(sourceKey);
                        assertThat(image.getContentSha256()).isEqualTo(expectedContentSha256);
                      });
            });
  }

  @Test
  @DisplayName("Should replace changed artwork and recompute derived metadata atomically")
  void shouldReplaceChangedArtworkAndRecomputeDerivedMetadataAtomically()
      throws NoSuchAlgorithmException {
    var entityId = UUID.randomUUID();
    var oldKey = "/old-poster.jpg";
    var newKey = "/new-poster.png";
    stubImageDownload(oldKey, createSolidPngImage(600, 900, 0x0000FF));
    publishImageEvent(entityId, oldKey, ImageRefreshMode.PRESERVE);

    var originalImages = awaitImages(entityId, oldKey);
    var originalIds = originalImages.stream().map(Image::getId).toList();
    var originalSmall =
        originalImages.stream()
            .filter(image -> image.getVariant() == ImageSize.SMALL)
            .findFirst()
            .orElseThrow();

    var newImageData = createSolidPngImage(600, 900, 0x00A0A0);
    stubImageDownload(newKey, newImageData);
    publishImageEvent(entityId, newKey, ImageRefreshMode.REFRESH_IF_CHANGED);

    var expectedContentSha256 =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(newImageData));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var replacements =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
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
                      replacement -> {
                        assertThat(replacement.getBlurHash())
                            .isNotEqualTo(originalSmall.getBlurHash());
                        assertThat(replacement.getAmbientColors())
                            .isNotEqualTo(originalSmall.getAmbientColors());
                      });
              assertThatThrownBy(() -> imageService.readImageFile(originalSmall))
                  .isInstanceOf(java.io.IOException.class);
            });
  }

  @Test
  @DisplayName("Should preserve existing artwork when changed image download fails")
  void shouldPreserveExistingArtworkWhenChangedImageDownloadFails() {
    var entityId = UUID.randomUUID();
    var oldKey = "/existing-poster.jpg";
    var failingKey = "/unavailable-poster.jpg";
    stubImageDownload(oldKey);
    publishImageEvent(entityId, oldKey, ImageRefreshMode.PRESERVE);
    var originalImages = awaitImages(entityId, oldKey);
    var originalIds = originalImages.stream().map(Image::getId).toList();

    wireMock.stubFor(get(urlPathEqualTo(failingKey)).willReturn(aResponse().withStatus(500)));
    publishImageEvent(entityId, failingKey, ImageRefreshMode.REFRESH_IF_CHANGED);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> wireMock.verify(getRequestedFor(urlPathEqualTo(failingKey))));
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              var preserved =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(preserved)
                  .extracting(Image::getId)
                  .containsExactlyInAnyOrderElementsOf(originalIds);
              assertThat(preserved)
                  .allSatisfy(image -> assertThat(image.getKey()).isEqualTo(oldKey));
              assertThat(preserved)
                  .allSatisfy(image -> assertThat(imageService.readImageFile(image)).isNotEmpty());
            });
  }

  @Test
  @DisplayName("Should roll back database replacement and preserve files when insert fails")
  void shouldRollBackDatabaseReplacementAndPreserveFilesWhenInsertFails() {
    var entityId = UUID.randomUUID();
    var oldKey = "/rollback-old.jpg";
    stubImageDownload(oldKey);
    publishImageEvent(entityId, oldKey, ImageRefreshMode.PRESERVE);
    var originalImages = awaitImages(entityId, oldKey);
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

  private void publishImageEvent(
      UUID entityId, String sourceKey, ImageRefreshMode imageRefreshMode) {
    transactionTemplate.executeWithoutResult(
        status ->
            eventPublisher.publishEvent(
                new MetadataEnrichedEvent(
                    entityId,
                    ImageEntityType.MOVIE,
                    List.of(new TmdbImageSource(ImageType.POSTER, sourceKey)),
                    imageRefreshMode)));
  }

  private List<Image> awaitImages(UUID entityId, String sourceKey) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        imageRepository.findByEntityIdAndEntityType(
                            entityId, ImageEntityType.MOVIE))
                    .hasSize(ImageSize.values().length)
                    .allSatisfy(image -> assertThat(image.getKey()).isEqualTo(sourceKey)));
    return imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
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
