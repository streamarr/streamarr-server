package com.streamarr.server.services.metadata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Image Enrichment Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageEnrichmentIT extends AbstractIntegrationTest {

  private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    wireMock.start();
    registry.add("tmdb.image.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.token", () -> "test-token");
  }

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ImageRepository imageRepository;

  @BeforeEach
  void resetStubs() {
    wireMock.resetAll();
  }

  @AfterAll
  static void tearDown() {
    wireMock.stop();
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
