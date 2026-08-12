package com.streamarr.server.services.metadata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.time.Duration;
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
    stubImageDownload("/backdrop.jpg");

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
                      small -> {
                        var ambient = small.getAmbientColors();
                        assertThat(ambient).isNotNull();
                        assertThat(
                                List.of(
                                    ambient.topLeft(),
                                    ambient.topRight(),
                                    ambient.bottomRight(),
                                    ambient.bottomLeft(),
                                    ambient.primary()))
                            .allSatisfy(hex -> assertThat(hex).matches("#[0-9a-f]{6}"));
                      });
            });
  }

  private void stubImageDownload(String path) {
    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(createTestImage(600, 900))));
  }
}
