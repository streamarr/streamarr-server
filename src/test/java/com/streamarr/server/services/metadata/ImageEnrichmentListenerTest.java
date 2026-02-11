package com.streamarr.server.services.metadata;

import static com.streamarr.server.fakes.TestImages.createTestImage;
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
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Image Enrichment Listener Tests")
class ImageEnrichmentListenerTest {

  private FakeImageRepository imageRepository;
  private FakeTmdbHttpService tmdbHttpService;
  private FileSystem fileSystem;
  private ImageEnrichmentListener listener;

  @BeforeEach
  void setUp() {
    imageRepository = new FakeImageRepository();
    tmdbHttpService = new FakeTmdbHttpService();
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageProperties = new ImageProperties("/data/images");
    var imageVariantService = new ImageVariantService();
    var imageService =
        new ImageService(imageRepository, imageVariantService, imageProperties, fileSystem);
    listener =
        new ImageEnrichmentListener(tmdbHttpService, imageService, new MutexFactoryProvider());
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
    assertThat(images)
        .extracting(Image::getImageType)
        .containsOnly(ImageType.POSTER, ImageType.BACKDROP);
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
    assertThat(images).extracting(Image::getImageType).containsOnly(ImageType.BACKDROP);
  }

  @Test
  @DisplayName("Should skip processing when images already exist for entity")
  void shouldSkipProcessingWhenImagesAlreadyExistForEntity() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));

    var existingImage =
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
    assertThat(images.getFirst().getId()).isEqualTo(existingImage.getId());
  }

  @Test
  @DisplayName("Should not save images for interrupted source when download is interrupted")
  void shouldNotSaveImagesForInterruptedSourceWhenDownloadIsInterrupted() {
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

    listener.onMetadataEnriched(event);

    var images = imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
    assertThat(images).extracting(Image::getImageType).containsOnly(ImageType.BACKDROP);
  }

  @Test
  @DisplayName("Should clean up written files when batch save fails")
  void shouldCleanUpWrittenFilesWhenBatchSaveFails() throws IOException {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    imageRepository.setFailOnInsertAllIfAbsent(true);

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    listener.onMetadataEnriched(event);

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .isEmpty();

    var entityDir =
        fileSystem.getPath("/data/images/movie").resolve(entityId.toString()).resolve("poster");
    try (var files = Files.list(entityDir)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  @DisplayName("Should not save images when all downloads fail")
  void shouldNotSaveImagesWhenAllDownloadsFail() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setFailAll(true);

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(
                new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
                new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg")));

    listener.onMetadataEnriched(event);

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .isEmpty();
  }

  private static class FakeTmdbHttpService extends TheMovieDatabaseHttpService {

    private byte[] imageData;
    private String failOnPath;
    private String interruptOnPath;
    private boolean failAll;

    FakeTmdbHttpService() {
      super("", "", "", 10, null, null);
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

    void setFailAll(boolean failAll) {
      this.failAll = failAll;
    }

    @Override
    public byte[] downloadImage(String pathFragment) throws IOException, InterruptedException {
      if (failAll) {
        throw new IOException("Simulated download failure for " + pathFragment);
      }
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
