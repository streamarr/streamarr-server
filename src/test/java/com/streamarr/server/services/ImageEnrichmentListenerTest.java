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
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Builder;
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
  @DisplayName("Should download only missing image types when some images already exist")
  void shouldDownloadOnlyMissingImageTypesWhenSomeImagesAlreadyExist() throws InterruptedException {
    var entityId = UUID.randomUUID();
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
    var imageDownloader =
        BlockingImageDownloader.builder().imageData(createTestImage(600, 900)).build();
    imageDownloader.expectDownload("/backdrop.jpg");
    var testListener = createListener(imageDownloader, new MutexFactory<>());
    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(
                new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
                new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg")));

    testListener.onMetadataEnriched(event);

    assertThat(imageDownloader.awaitDownloadStarted("/backdrop.jpg")).isTrue();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        imageRepository.findByEntityIdAndEntityType(
                            entityId, ImageEntityType.MOVIE))
                    .extracting(Image::getImageType)
                    .containsOnly(ImageType.POSTER, ImageType.BACKDROP));
    assertThat(imageDownloader.downloadedPaths()).containsExactly("/backdrop.jpg");
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
  void shouldStillProcessImagesWhenLockIsBrieflyContestedByAnotherThread()
      throws InterruptedException {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    var mutex = SignalingMutex.builder().expectedLockAttempts(1).expectedUnlocks(2).build();
    var testListener = createListener(tmdbHttpService, new FixedMutexFactory(mutex));
    mutex.lock();
    try {
      testListener.onMetadataEnriched(
          new MetadataEnrichedEvent(
              entityId,
              ImageEntityType.MOVIE,
              List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg"))));

      assertThat(mutex.awaitLockAttempts()).isTrue();
    } finally {
      mutex.unlock();
    }

    assertThat(mutex.awaitUnlocks()).isTrue();
    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .isNotEmpty();
  }

  @Test
  @DisplayName("Should enrich only once when concurrent events received for same entity")
  void shouldEnrichOnlyOnceWhenConcurrentEventsReceivedForSameEntity() throws InterruptedException {
    var entityId = UUID.randomUUID();
    var imagePath = "/poster.jpg";
    var imageDownloader =
        BlockingImageDownloader.builder()
            .imageData(createTestImage(600, 900))
            .blockedPath(imagePath)
            .build();
    imageDownloader.expectDownload(imagePath);
    var mutex = SignalingMutex.builder().expectedLockAttempts(2).expectedUnlocks(2).build();
    var testListener = createListener(imageDownloader, new FixedMutexFactory(mutex));
    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, imagePath)));

    testListener.onMetadataEnriched(event);
    assertThat(imageDownloader.awaitDownloadStarted(imagePath)).isTrue();

    try {
      testListener.onMetadataEnriched(event);
      assertThat(mutex.awaitLockAttempts()).isTrue();
    } finally {
      imageDownloader.releaseBlockedDownload();
    }

    assertThat(mutex.awaitUnlocks()).isTrue();
    assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
        .isNotEmpty();
    assertThat(imageDownloader.downloadedPaths()).containsExactly(imagePath);
  }

  @Test
  @DisplayName("Should enrich different entities concurrently when one entity is blocked")
  void shouldEnrichDifferentEntitiesConcurrentlyWhenOneEntityIsBlocked()
      throws InterruptedException {
    var blockedEntityId = UUID.randomUUID();
    var independentEntityId = UUID.randomUUID();
    var blockedPath = "/blocked-poster.jpg";
    var independentPath = "/independent-poster.jpg";
    var imageDownloader =
        BlockingImageDownloader.builder()
            .imageData(createTestImage(600, 900))
            .blockedPath(blockedPath)
            .build();
    var completionTrackingRepository = new CompletionTrackingImageRepository();
    imageRepository = completionTrackingRepository;
    imageDownloader.expectDownload(blockedPath);
    imageDownloader.expectDownload(independentPath);
    completionTrackingRepository.expectImagesFor(blockedEntityId);
    completionTrackingRepository.expectImagesFor(independentEntityId);
    var testListener = createListener(imageDownloader, new MutexFactory<>());

    testListener.onMetadataEnriched(
        new MetadataEnrichedEvent(
            blockedEntityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, blockedPath))));
    assertThat(imageDownloader.awaitDownloadStarted(blockedPath)).isTrue();

    try {
      testListener.onMetadataEnriched(
          new MetadataEnrichedEvent(
              independentEntityId,
              ImageEntityType.MOVIE,
              List.of(new TmdbImageSource(ImageType.POSTER, independentPath))));

      assertThat(completionTrackingRepository.awaitImagesFor(independentEntityId))
          .as("Independent entity should finish while the first entity remains blocked")
          .isTrue();
    } finally {
      imageDownloader.releaseBlockedDownload();
    }

    assertThat(completionTrackingRepository.awaitImagesFor(blockedEntityId)).isTrue();
    assertThat(imageDownloader.downloadedPaths())
        .containsExactlyInAnyOrder(blockedPath, independentPath);
  }

  @Test
  @DisplayName("Should stop enrichment when interrupted while waiting for entity lock")
  void shouldStopEnrichmentWhenInterruptedWhileWaitingForEntityLock() throws InterruptedException {
    var entityId = UUID.randomUUID();
    tmdbHttpService.setImageData(createTestImage(600, 900));
    var mutex = SignalingMutex.builder().expectedLockAttempts(1).build();
    var testListener = createListener(tmdbHttpService, new FixedMutexFactory(mutex));
    var event =
        new MetadataEnrichedEvent(
            entityId,
            ImageEntityType.MOVIE,
            List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")));

    mutex.lock();
    try {
      testListener.onMetadataEnriched(event);

      assertThat(mutex.awaitLockAttempts()).isTrue();
      var waitingThread = mutex.interruptibleThread();
      waitingThread.interrupt();
      assertThat(waitingThread.join(Duration.ofSeconds(5))).isTrue();
      assertThat(waitingThread.isInterrupted()).isTrue();
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

  private static final class CompletionTrackingImageRepository extends FakeImageRepository {

    private final ConcurrentHashMap<UUID, CountDownLatch> expectedImages =
        new ConcurrentHashMap<>();

    private void expectImagesFor(UUID entityId) {
      expectedImages.put(entityId, new CountDownLatch(1));
    }

    private boolean awaitImagesFor(UUID entityId) throws InterruptedException {
      return expectedImages.get(entityId).await(5, TimeUnit.SECONDS);
    }

    @Override
    public Set<UUID> insertAllIfAbsent(List<Image> images) {
      var insertedImageIds = super.insertAllIfAbsent(images);
      images.stream()
          .map(Image::getEntityId)
          .distinct()
          .map(expectedImages::get)
          .filter(java.util.Objects::nonNull)
          .forEach(CountDownLatch::countDown);
      return insertedImageIds;
    }
  }

  private static final class BlockingImageDownloader implements TmdbImageDownloader {

    private final byte[] imageData;
    private final String blockedPath;
    private final CountDownLatch releaseBlockedDownload = new CountDownLatch(1);
    private final ConcurrentHashMap<String, CountDownLatch> expectedDownloads =
        new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> downloadedPaths = new ConcurrentLinkedQueue<>();

    @Builder
    private BlockingImageDownloader(byte[] imageData, String blockedPath) {
      this.imageData = imageData;
      this.blockedPath = blockedPath;
    }

    private void expectDownload(String path) {
      expectedDownloads.put(path, new CountDownLatch(1));
    }

    private boolean awaitDownloadStarted(String path) throws InterruptedException {
      return expectedDownloads.get(path).await(5, TimeUnit.SECONDS);
    }

    private void releaseBlockedDownload() {
      releaseBlockedDownload.countDown();
    }

    private List<String> downloadedPaths() {
      return List.copyOf(downloadedPaths);
    }

    @Override
    public byte[] downloadImage(String pathFragment) throws IOException, InterruptedException {
      downloadedPaths.add(pathFragment);
      var expectedDownload = expectedDownloads.get(pathFragment);
      if (expectedDownload != null) {
        expectedDownload.countDown();
      }
      if (pathFragment.equals(blockedPath) && !releaseBlockedDownload.await(5, TimeUnit.SECONDS)) {
        throw new IOException("Blocked image download was not released");
      }
      return imageData;
    }
  }

  private static final class FixedMutexFactory extends MutexFactory<String> {

    private final ReentrantLock mutex;

    private FixedMutexFactory(ReentrantLock mutex) {
      this.mutex = mutex;
    }

    @Override
    public ReentrantLock getMutex(String key) {
      return mutex;
    }
  }

  private static final class SignalingMutex extends ReentrantLock {

    private final CountDownLatch lockAttempts;
    private final CountDownLatch unlocks;
    private final AtomicReference<Thread> interruptibleThread = new AtomicReference<>();

    @Builder
    private SignalingMutex(int expectedLockAttempts, int expectedUnlocks) {
      lockAttempts = new CountDownLatch(expectedLockAttempts);
      unlocks = new CountDownLatch(expectedUnlocks);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      interruptibleThread.set(Thread.currentThread());
      lockAttempts.countDown();
      super.lockInterruptibly();
    }

    @Override
    public void unlock() {
      super.unlock();
      unlocks.countDown();
    }

    private boolean awaitLockAttempts() throws InterruptedException {
      return lockAttempts.await(5, TimeUnit.SECONDS);
    }

    private boolean awaitUnlocks() throws InterruptedException {
      return unlocks.await(5, TimeUnit.SECONDS);
    }

    private Thread interruptibleThread() {
      return interruptibleThread.get();
    }
  }
}
