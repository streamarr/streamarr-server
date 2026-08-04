package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
    listener = createListener(tmdbHttpService, new MutexFactory<>());
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

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images)
                  .extracting(Image::getImageType)
                  .containsOnly(ImageType.POSTER, ImageType.BACKDROP);
            });
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

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images).extracting(Image::getImageType).containsOnly(ImageType.BACKDROP);
            });
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

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images).hasSize(1);
              assertThat(images.getFirst().getId()).isEqualTo(existingImage.getId());
            });
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

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images).extracting(Image::getImageType).containsOnly(ImageType.BACKDROP);
            });
  }

  @Test
  @DisplayName("Should clean up written files when batch save fails")
  void shouldCleanUpWrittenFilesWhenBatchSaveFails() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    imageRepository.setFailOnInsertAllIfAbsent(true);

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    listener.onMetadataEnriched(event);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(
                      imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
                  .isEmpty();

              var entityDir =
                  fileSystem
                      .getPath("/data/images/movie")
                      .resolve(entityId.toString())
                      .resolve("poster");
              try (var files = Files.list(entityDir)) {
                assertThat(files).isEmpty();
              }
            });
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

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        imageRepository.findByEntityIdAndEntityType(
                            entityId, ImageEntityType.MOVIE))
                    .isEmpty());
  }

  @Test
  @DisplayName("Should not block calling thread when downloading images")
  void shouldNotBlockCallingThreadWhenDownloadingImages() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    tmdbHttpService.setDelayMillis(500);

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    listener.onMetadataEnriched(event);

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .as("Method should return before async download completes")
        .isEmpty();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images).isNotEmpty();
            });
  }

  @Test
  @DisplayName("Should still process images when lock is briefly contested by another thread")
  void shouldStillProcessImagesWhenLockIsBrieflyContestedByAnotherThread() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));

    var sharedFactory = new MutexFactory<String>();
    var mutex = sharedFactory.getMutex(entityId.toString());

    var testListener = createListener(tmdbHttpService, sharedFactory);

    mutex.lock();

    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    testListener.onMetadataEnriched(event);

    await().atMost(Duration.ofSeconds(5)).until(mutex::hasQueuedThreads);
    mutex.unlock();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var images =
                  imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE);
              assertThat(images).isNotEmpty();
            });
  }

  @Test
  @DisplayName("Should enrich only once when concurrent events received for same entity")
  void shouldEnrichOnlyOnceWhenConcurrentEventsReceivedForSameEntity() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    tmdbHttpService.setDelayMillis(500);

    var downloadCount = new AtomicInteger();
    TmdbImageDownloader countingDownloader =
        path -> {
          downloadCount.incrementAndGet();
          return tmdbHttpService.downloadImage(path);
        };
    var sharedFactory = new MutexFactory<String>();
    var mutex = sharedFactory.getMutex(entityId.toString());
    var testListener = createListener(countingDownloader, sharedFactory);
    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    testListener.onMetadataEnriched(event);
    await().atMost(Duration.ofSeconds(5)).until(() -> downloadCount.get() == 1);

    testListener.onMetadataEnriched(event);
    await().atMost(Duration.ofSeconds(5)).until(mutex::hasQueuedThreads);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(mutex.isLocked()).isFalse();
              assertThat(mutex.hasQueuedThreads()).isFalse();
              assertThat(
                      imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
                  .isNotEmpty();
            });

    assertThat(downloadCount).hasValue(1);
  }

  @Test
  @DisplayName("Should stop enrichment when interrupted while waiting for entity lock")
  void shouldStopEnrichmentWhenInterruptedWhileWaitingForEntityLock() {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));

    var waitingThread = new AtomicReference<Thread>();
    var mutex =
        new ReentrantLock() {
          @Override
          public void lockInterruptibly() throws InterruptedException {
            waitingThread.set(Thread.currentThread());
            super.lockInterruptibly();
          }
        };
    var sharedFactory =
        new MutexFactory<String>() {
          @Override
          public ReentrantLock getMutex(String key) {
            return mutex;
          }
        };
    var testListener = createListener(tmdbHttpService, sharedFactory);
    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    mutex.lock();
    try {
      testListener.onMetadataEnriched(event);

      await().atMost(Duration.ofSeconds(5)).until(() -> waitingThread.get() != null);
      waitingThread.get().interrupt();
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> waitingThread.get().getState() == Thread.State.TERMINATED);

      assertThat(waitingThread.get().isInterrupted()).isTrue();
    } finally {
      mutex.unlock();
    }

    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .isEmpty();
  }

  private ImageEnrichmentListener createListener(
      TmdbImageDownloader imageDownloader, MutexFactory<String> mutexFactory) {
    var mutexFactoryProvider =
        new MutexFactoryProvider() {
          @Override
          @SuppressWarnings("unchecked")
          public <K> MutexFactory<K> getMutexFactory() {
            return (MutexFactory<K>) mutexFactory;
          }
        };
    var imageService =
        new ImageService(
            imageRepository,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            fileSystem);
    return new ImageEnrichmentListener(imageDownloader, imageService, mutexFactoryProvider);
  }
}
