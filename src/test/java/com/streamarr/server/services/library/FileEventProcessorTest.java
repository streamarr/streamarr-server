package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.LibraryScanProperties;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.ProbeTaskSchedulingException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
import com.streamarr.server.fakes.FakeLibraryMutationTransaction;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.MediaFileProbeTaskRequested;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.ExternalIdVideoFileMetadataParser;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import io.methvin.watcher.DirectoryChangeEvent;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

@Tag("UnitTest")
@DisplayName("File Event Processor Tests")
class FileEventProcessorTest {

  private FileSystem fileSystem;
  private LibraryRepository libraryRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private AtomicReference<FileStabilityChecker> stabilityCheckerRef;
  private AtomicReference<ApplicationEventPublisher> eventPublisherRef;
  private FileEventProcessor eventProcessor;
  private UUID specialLibraryId;

  @BeforeEach
  void setUp() throws IOException {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    libraryRepository = new FakeLibraryRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    var ignoredFileValidator =
        new IgnoredFileValidator(new LibraryScanProperties(null, null, null));
    var videoExtensionValidator = new VideoExtensionValidator();
    stabilityCheckerRef = new AtomicReference<>(path -> true);
    eventPublisherRef = new AtomicReference<>(_ -> {});

    // Plain paths instead of file:// URIs because file:// URIs can't round-trip through Jimfs.
    var library =
        Library.builder()
            .name("Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri("file:///media/movies")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build();

    libraryRepository.save(library);
    var specialLibrary =
        Library.builder()
            .name("Special Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri("file:///media/movies/special")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build();
    specialLibraryId = libraryRepository.save(specialLibrary).getId();

    var seriesLibrary =
        Library.builder()
            .name("TV Shows")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri("file:///media/shows")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.SERIES)
            .build();
    libraryRepository.save(seriesLibrary);

    Files.createDirectories(fileSystem.getPath("/media/movies/special"));
    Files.createDirectories(fileSystem.getPath("/media/movies"));
    Files.createDirectories(fileSystem.getPath("/media/shows"));

    var movieService = mock(MovieService.class);
    @SuppressWarnings("unchecked")
    MetadataProvider<Movie> tmdbProvider = mock(TMDBMovieProvider.class);

    var movieFileProcessor =
        new MovieFileProcessor(
            new DefaultVideoFileMetadataParser(),
            new ExternalIdVideoFileMetadataParser(),
            new MovieMetadataProviderResolver(List.of(tmdbProvider)),
            movieService,
            mediaFileRepository,
            new MutexFactoryProvider());

    var seriesFileProcessor = mock(SeriesFileProcessor.class);
    var seriesService = mock(SeriesService.class);
    var mutationTransactions =
        new MutationTransactions(new FakeTransactionManager(), new ConstraintViolationTranslator());

    var libraryManagementService =
        new LibraryManagementService(
            ignoredFileValidator,
            videoExtensionValidator,
            movieFileProcessor,
            seriesFileProcessor,
            libraryRepository,
            new FakeLibraryMetadataRepository(),
            mediaFileRepository,
            movieService,
            seriesService,
            event -> eventPublisherRef.get().publishEvent(event),
            new MutexFactoryProvider(),
            mock(LibraryRefreshService.class),
            fileSystem,
            new FakeLibraryMutationTransaction(),
            mutationTransactions);

    eventProcessor =
        new FileEventProcessor(
            path -> stabilityCheckerRef.get().waitForStability(path),
            libraryManagementService,
            ignoredFileValidator);

    eventProcessor.reset(libraryRepository.findAll());
  }

  @AfterEach
  void tearDown() throws IOException {
    eventProcessor.shutdown();
    fileSystem.close();
  }

  @Test
  @DisplayName(
      "Should recover a probe request when snapshot reading fails without another file event")
  void shouldRecoverAProbeRequestWhenSnapshotReadingFailsWithoutAnotherFileEvent()
      throws Exception {
    var path = createFile("/media/shows/Show.S01E01.mkv");
    var events = new CapturingEventPublisher();
    var unavailable = new AtomicBoolean(true);
    eventPublisherRef.set(
        event -> {
          if (event instanceof MediaFileProbeTaskRequested request
              && unavailable.getAndSet(false)) {
            throw new ProbeTaskSchedulingException(
                request.mediaFileId(), new IOException("offline"));
          }

          events.publishEvent(event);
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(events.getEventsOfType(MediaFileProbeTaskRequested.class)).hasSize(1));
  }

  private enum Cancellation {
    DELETE,
    RESET,
    SHUTDOWN
  }

  @ParameterizedTest
  @EnumSource(Cancellation.class)
  @DisplayName("Should cancel a pending probe request when its watcher work is cancelled")
  void shouldCancelAPendingProbeRequestWhenItsWatcherWorkIsCancelled(Cancellation cancellation)
      throws Exception {
    var path = createFile("/media/shows/Show.S01E01.mkv");
    var attempted = new CountDownLatch(1);
    var worker = new AtomicReference<Thread>();
    var attempts = new AtomicInteger();
    eventPublisherRef.set(
        event -> {
          if (event instanceof MediaFileProbeTaskRequested request) {
            worker.set(Thread.currentThread());
            attempts.incrementAndGet();
            attempted.countDown();
            throw new ProbeTaskSchedulingException(
                request.mediaFileId(), new IOException("offline"));
          }
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();

    Runnable cancel =
        switch (cancellation) {
          case DELETE ->
              () -> eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.DELETE, path);
          case RESET -> () -> eventProcessor.reset(libraryRepository.findAll());
          case SHUTDOWN -> eventProcessor::shutdown;
        };
    cancel.run();

    assertThat(worker.get().join(Duration.ofSeconds(5))).isTrue();
    assertThat(attempts).hasValue(1);
  }

  @Test
  @DisplayName("Should accept a later file event when processing fails for a non-retryable reason")
  void shouldAcceptALaterFileEventWhenProcessingFailsForANonRetryableReason() throws Exception {
    var path = createFile("/media/shows/Show.S01E01.mkv");
    var attempted = new CountDownLatch(1);
    var worker = new AtomicReference<Thread>();
    var attempts = new AtomicInteger();
    eventPublisherRef.set(
        _ -> {
          worker.set(Thread.currentThread());
          attempts.incrementAndGet();
          attempted.countDown();
          throw new IllegalStateException("unsupported request");
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(worker.get().join(Duration.ofSeconds(5))).isTrue();
    assertThat(attempts).hasValue(1);

    var events = new CapturingEventPublisher();
    eventPublisherRef.set(events);
    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.MODIFY, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(events.getEventsOfType(MediaFileProbeTaskRequested.class)).hasSize(1));
  }

  @Test
  @DisplayName("Should not process events when shut down")
  void shouldNotProcessEventsWhenShutDown() throws Exception {
    eventProcessor.shutdown();

    var path = createFile("/media/movies/Movie (2024).mkv");
    var processed = new AtomicBoolean(false);
    stabilityCheckerRef.set(
        p -> {
          processed.set(true);
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(processed.get()).isFalse());
  }

  @Test
  @DisplayName("Should not throw when overflow event received")
  void shouldNotThrowWhenOverflowEventReceived() {
    var path = fileSystem.getPath("/media/movies/file.mkv");
    var processed = new AtomicBoolean(false);
    stabilityCheckerRef.set(
        p -> {
          processed.set(true);
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.OVERFLOW, path);

    assertThat(processed.get()).isFalse();
  }

  @Test
  @DisplayName("Should not process file when extension is unsupported")
  void shouldNotProcessFileWhenExtensionIsUnsupported() throws Exception {
    var path = createFile("/media/movies/readme.txt");
    var processed = new AtomicBoolean(false);
    stabilityCheckerRef.set(
        p -> {
          processed.set(true);
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    assertThat(processed.get()).isFalse();
  }

  @Test
  @DisplayName("Should process file when stable")
  void shouldProcessFileWhenStable() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var mediaFile =
                  mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(path));
              assertThat(mediaFile).isPresent();
            });
  }

  @Test
  @DisplayName("Should not process file when unstable")
  void shouldNotProcessFileWhenUnstable() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var latch = new CountDownLatch(1);

    stabilityCheckerRef.set(
        p -> {
          latch.countDown();
          return false;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    var mediaFile = mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(path));
    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should deduplicate when multiple events for same path")
  void shouldDeduplicateWhenMultipleEventsForSamePath() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var stabilityCallCount = new AtomicInteger(0);
    var blockLatch = new CountDownLatch(1);
    var enteredLatch = new CountDownLatch(1);

    stabilityCheckerRef.set(
        p -> {
          stabilityCallCount.incrementAndGet();
          enteredLatch.countDown();
          try {
            blockLatch.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.MODIFY, path);

    blockLatch.countDown();

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(stabilityCallCount.get()).isEqualTo(1));
  }

  @Test
  @DisplayName("Should skip stability check when no library matches path")
  void shouldSkipStabilityCheckWhenNoLibraryMatchesPath() throws Exception {
    var otherDir = fileSystem.getPath("/other");
    Files.createDirectories(otherDir);
    var path = createFileAt(otherDir, "Movie (2024).mkv");
    var stabilityCheckerCalled = new AtomicBoolean(false);

    stabilityCheckerRef.set(
        p -> {
          stabilityCheckerCalled.set(true);
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(stabilityCheckerCalled.get()).isFalse());

    var mediaFile = mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(path));
    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should resolve to longest match when library paths overlap")
  void shouldResolveToLongestMatchWhenLibraryPathsOverlap() throws Exception {
    var path = createFileAt(fileSystem.getPath("/media/movies/special"), "Movie (2024).mkv");

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var mediaFile =
                  mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(path));
              assertThat(mediaFile).isPresent();
              assertThat(mediaFile.get().getLibraryId()).isEqualTo(specialLibraryId);
            });
  }

  @Test
  @DisplayName("Should skip stability check when path shares string prefix but not path prefix")
  void shouldSkipStabilityCheckWhenPathSharesStringPrefixButNotPathPrefix() throws Exception {
    Files.createDirectories(fileSystem.getPath("/media/moviesfoo"));
    var path = createFileAt(fileSystem.getPath("/media/moviesfoo"), "Movie (2024).mkv");
    var stabilityCheckerCalled = new AtomicBoolean(false);

    stabilityCheckerRef.set(
        p -> {
          stabilityCheckerCalled.set(true);
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(stabilityCheckerCalled.get()).isFalse());

    var mediaFile = mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(path));
    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should clean up in-flight map when processing completes")
  void shouldCleanUpInFlightMapWhenProcessingCompletes() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var callCount = new AtomicInteger(0);

    stabilityCheckerRef.set(
        p -> {
          callCount.incrementAndGet();
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.MODIFY, path);
              }
              return callCount.get() >= 2;
            });

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should clean up in-flight map when stability fails")
  void shouldCleanUpInFlightMapWhenStabilityFails() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var callCount = new AtomicInteger(0);

    stabilityCheckerRef.set(
        p -> {
          var count = callCount.incrementAndGet();
          return count != 1;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
              }
              return callCount.get() >= 2;
            });

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should interrupt stability check when file is deleted")
  void shouldInterruptStabilityCheckWhenFileIsDeleted() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var enteredLatch = new CountDownLatch(1);
    var interruptedLatch = new CountDownLatch(1);

    stabilityCheckerRef.set(
        p -> {
          enteredLatch.countDown();
          try {
            // Block until interrupted; bounded to prevent test hang if cancel fails
            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            interruptedLatch.countDown();
            return false;
          }
          return false;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.DELETE, path);

    assertThat(interruptedLatch.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("Should remove from in-flight map when delete event received")
  void shouldRemoveFromInFlightMapWhenDeleteEventReceived() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var firstEnteredLatch = new CountDownLatch(1);
    var firstBlockLatch = new CountDownLatch(1);
    var callCount = new AtomicInteger(0);

    stabilityCheckerRef.set(
        p -> {
          var count = callCount.incrementAndGet();
          if (count == 1) {
            firstEnteredLatch.countDown();
            try {
              firstBlockLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
              return false;
            }
            return false;
          }
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(firstEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.DELETE, path);
    firstBlockLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
              }
              return callCount.get() >= 2;
            });

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should clean up in-flight map when processing throws exception")
  void shouldCleanUpInFlightMapWhenProcessingThrowsException() throws Exception {
    var path = createFile("/media/shows/Show S01E01 (2024).mkv");
    var callCount = new AtomicInteger(0);

    stabilityCheckerRef.set(
        p -> {
          callCount.incrementAndGet();
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
              }
              return callCount.get() >= 2;
            });

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should skip stability check when library added after reset")
  void shouldSkipStabilityCheckWhenLibraryAddedAfterReset() throws Exception {
    var laterLibrary =
        Library.builder()
            .name("Anime")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri("file:///media/anime")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build();
    libraryRepository.save(laterLibrary);
    Files.createDirectories(fileSystem.getPath("/media/anime"));

    var path = createFile("/media/anime/Movie (2024).mkv");
    var stabilityCheckerCalled = new AtomicBoolean(false);

    stabilityCheckerRef.set(
        p -> {
          stabilityCheckerCalled.set(true);
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(stabilityCheckerCalled.get()).isFalse());

    var mediaFile = mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(path));
    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should interrupt in-flight stability checks when shut down")
  void shouldInterruptInFlightStabilityChecksWhenShutDown() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var enteredLatch = new CountDownLatch(1);
    var interruptedLatch = new CountDownLatch(1);

    stabilityCheckerRef.set(
        p -> {
          enteredLatch.countDown();
          try {
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            interruptedLatch.countDown();
            return false;
          }
          return false;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    eventProcessor.shutdown();

    assertThat(interruptedLatch.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("Should reprocess file when delete cancels in-flight check")
  void shouldReprocessFileWhenDeleteCancelsInFlightCheck() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var firstCheckEntered = new CountDownLatch(1);
    var firstCheckBlocked = new CountDownLatch(1);
    var secondCheckCompleted = new CountDownLatch(1);
    var callCount = new AtomicInteger(0);

    stabilityCheckerRef.set(
        p -> {
          var count = callCount.incrementAndGet();
          if (count == 1) {
            firstCheckEntered.countDown();
            try {
              firstCheckBlocked.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
              return false;
            }
            return false;
          }
          secondCheckCompleted.countDown();
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(firstCheckEntered.await(5, TimeUnit.SECONDS)).isTrue();

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.DELETE, path);
    firstCheckBlocked.countDown();

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    assertThat(secondCheckCompleted.await(5, TimeUnit.SECONDS))
        .as(
            "Second stability check should complete, proving system recovered from cancelled first check")
        .isTrue();
  }

  @Test
  @DisplayName("Should skip stability check when path is a directory")
  void shouldSkipStabilityCheckWhenPathIsDirectory() throws Exception {
    var dir = fileSystem.getPath("/media/movies/Season 04");
    Files.createDirectories(dir);
    var stabilityCheckerCalled = new AtomicBoolean(false);

    stabilityCheckerRef.set(
        p -> {
          stabilityCheckerCalled.set(true);
          return true;
        });

    eventProcessor.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, dir);

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(stabilityCheckerCalled.get()).isFalse());
  }

  private Path createFile(String pathStr) throws IOException {
    var path = fileSystem.getPath(pathStr);
    Files.createDirectories(path.getParent());
    Files.createFile(path);
    return path;
  }

  private Path createFileAt(Path directory, String filename) throws IOException {
    var path = directory.resolve(filename);
    Files.createFile(path);
    return path;
  }
}
