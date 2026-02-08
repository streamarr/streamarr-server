package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Image Enrichment Listener Tests")
class ImageEnrichmentListenerTest {

  private FakeImageRepository imageRepository;
  private FakeTmdbHttpService tmdbHttpService;
  private ImageEnrichmentListener listener;

  @BeforeEach
  void setUp() {
    imageRepository = new FakeImageRepository();
    tmdbHttpService = new FakeTmdbHttpService();
    var fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageProperties = new ImageProperties("/data/images");
    var imageVariantService = new ImageVariantService();
    var imageService =
        new ImageService(imageRepository, imageVariantService, imageProperties, fileSystem);
    listener =
        new ImageEnrichmentListener(
            tmdbHttpService, imageService, imageRepository, new MutexFactoryProvider());
  }

  @Test
  @DisplayName("Should download and process image for each source when event received")
  void shouldDownloadAndProcessImageForEachSourceWhenEventReceived() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(
                new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
                new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg")));

    listener.onMetadataEnriched(event);

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(images).hasSize(8);
  }

  @Test
  @DisplayName("Should continue processing remaining sources when one download fails")
  void shouldContinueProcessingRemainingSourcesWhenOneDownloadFails() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    tmdbHttpService.setFailOnPath("/poster.jpg");

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(
                new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
                new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg")));

    listener.onMetadataEnriched(event);

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(images).hasSize(4);
  }

  @Test
  @DisplayName("Should skip processing when images already exist for entity")
  void shouldSkipProcessingWhenImagesAlreadyExistForEntity() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));

    imageRepository.save(
        Image.builder()
            .entityId(entityId)
            .entityType(ImageEntityType.MOVIE)
            .imageType(ImageType.POSTER)
            .variant(ImageSize.SMALL)
            .width(185)
            .height(278)
            .path("movie/" + entityId + "/poster/small.jpg")
            .build());

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    listener.onMetadataEnriched(event);

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(images).hasSize(1);
  }

  @Test
  @DisplayName("Should restore interrupt flag when download is interrupted")
  void shouldRestoreInterruptFlagWhenDownloadInterrupted() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    tmdbHttpService.setInterruptOnPath("/poster.jpg");

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    try {
      listener.onMetadataEnriched(event);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should stop processing remaining sources when interrupted")
  void shouldStopProcessingRemainingSourcesWhenInterrupted() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    tmdbHttpService.setInterruptOnPath("/poster.jpg");

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(
                new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
                new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg")));

    try {
      listener.onMetadataEnriched(event);
    } finally {
      Thread.interrupted();
    }

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(images).isEmpty();
  }

  private byte[] createTestImage(int width, int height) {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(Color.BLUE);
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();

    try (var outputStream = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", outputStream);
      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static class FakeTmdbHttpService extends TheMovieDatabaseHttpService {

    private byte[] imageData;
    private String failOnPath;
    private String interruptOnPath;

    FakeTmdbHttpService() {
      super("", "", "", null, null);
    }

    void setImageData(byte[] imageData) {
      this.imageData = imageData;
    }

    void setFailOnPath(String path) {
      this.failOnPath = path;
    }

    void setInterruptOnPath(String path) {
      this.interruptOnPath = path;
    }

    @Override
    public byte[] downloadImage(String pathFragment)
        throws IOException, InterruptedException {
      if (pathFragment.equals(failOnPath)) {
        throw new IOException("Simulated download failure for " + pathFragment);
      }
      if (pathFragment.equals(interruptOnPath)) {
        throw new InterruptedException("Simulated interrupt for " + pathFragment);
      }
      return imageData;
    }
  }
}
