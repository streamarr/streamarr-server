package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
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

@Tag("UnitTest")
@DisplayName("Directory Watching Service Tests")
class DirectoryWatchingServiceTest {

  private FileSystem fileSystem;
  private LibraryRepository libraryRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private VideoExtensionValidator videoExtensionValidator;
  private AtomicReference<FileStabilityChecker> stabilityCheckerRef;
  private AtomicReference<LibraryManagementService> libraryManagementServiceRef;
  private DirectoryWatchingService watchingService;
  private UUID libraryId;

  @BeforeEach
  void setUp() throws IOException {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    libraryRepository = new FakeLibraryRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    videoExtensionValidator = new VideoExtensionValidator();
    stabilityCheckerRef = new AtomicReference<>(path -> true);
    libraryManagementServiceRef = new AtomicReference<>();

    var library =
        Library.builder()
            .name("Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepath("/media/movies")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build();

    var saved = libraryRepository.save(library);
    libraryId = saved.getId();

    Files.createDirectories(fileSystem.getPath("/media/movies"));

    var movieService = mock(MovieService.class);
    var personService = mock(PersonService.class);
    var genreService = mock(GenreService.class);
    @SuppressWarnings("unchecked")
    MetadataProvider<com.streamarr.server.domain.media.Movie> tmdbProvider =
        mock(TMDBMovieProvider.class);

    var libraryManagementService =
        new LibraryManagementService(
            videoExtensionValidator,
            new DefaultVideoFileMetadataParser(),
            new MovieMetadataProviderResolver(List.of(tmdbProvider)),
            libraryRepository,
            mediaFileRepository,
            movieService,
            personService,
            genreService,
            new MutexFactoryProvider(),
            fileSystem);
    libraryManagementServiceRef.set(libraryManagementService);

    watchingService =
        new DirectoryWatchingService(
            libraryRepository,
            path -> stabilityCheckerRef.get().awaitStability(path),
            libraryManagementServiceRef.get(),
            videoExtensionValidator);
  }

  @AfterEach
  void tearDown() throws IOException {
    watchingService.stopWatching();
    fileSystem.close();
  }

  @Test
  @DisplayName("Should not process events after stopping")
  void shouldNotProcessEventsAfterStopping() throws Exception {
    watchingService.stopWatching();

    var path = createFile("/media/movies/Movie (2024).mkv");
    var processed = new AtomicBoolean(false);
    stabilityCheckerRef.set(
        p -> {
          processed.set(true);
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(processed.get()).isFalse());
  }

  @Test
  @DisplayName("Should handle overflow event without throwing")
  void shouldHandleOverflowEventWithoutThrowing() {
    var path = fileSystem.getPath("/media/movies/file.mkv");
    var processed = new AtomicBoolean(false);
    stabilityCheckerRef.set(
        p -> {
          processed.set(true);
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.OVERFLOW, path);

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

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    assertThat(processed.get()).isFalse();
  }

  @Test
  @DisplayName("Should process file when stable")
  void shouldProcessFileWhenStable() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var mediaFile =
                  mediaFileRepository.findFirstByFilepath(path.toAbsolutePath().toString());
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

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    var mediaFile = mediaFileRepository.findFirstByFilepath(path.toAbsolutePath().toString());
    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should deduplicate events for same path")
  void shouldDeduplicateEventsForSamePath() throws Exception {
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
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.MODIFY, path);

    blockLatch.countDown();

    await()
        .during(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(stabilityCallCount.get()).isEqualTo(1));
  }

  @Test
  @DisplayName("Should ignore file when no library matches path")
  void shouldIgnoreFileWhenNoLibraryMatchesPath() throws Exception {
    var otherDir = fileSystem.getPath("/other");
    Files.createDirectories(otherDir);
    var path = createFileAt(otherDir, "Movie (2024).mkv");
    var latch = new CountDownLatch(1);

    stabilityCheckerRef.set(
        p -> {
          latch.countDown();
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    var mediaFile = mediaFileRepository.findFirstByFilepath(path.toAbsolutePath().toString());
    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should resolve to longest match when library paths overlap")
  void shouldResolveToLongestMatchWhenLibraryPathsOverlap() throws Exception {
    var specialLibrary =
        Library.builder()
            .name("Special Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepath("/media/movies/special")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build();

    var savedSpecial = libraryRepository.save(specialLibrary);
    Files.createDirectories(fileSystem.getPath("/media/movies/special"));

    var path = createFileAt(fileSystem.getPath("/media/movies/special"), "Movie (2024).mkv");

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var mediaFile =
                  mediaFileRepository.findFirstByFilepath(path.toAbsolutePath().toString());
              assertThat(mediaFile).isPresent();
              assertThat(mediaFile.get().getLibraryId()).isEqualTo(savedSpecial.getId());
            });
  }

  @Test
  @DisplayName("Should not match library when path shares string prefix but not path prefix")
  void shouldNotMatchLibraryWhenPathSharesStringPrefixButNotPathPrefix() throws Exception {
    Files.createDirectories(fileSystem.getPath("/media/moviesfoo"));
    var path = createFileAt(fileSystem.getPath("/media/moviesfoo"), "Movie (2024).mkv");
    var latch = new CountDownLatch(1);

    stabilityCheckerRef.set(
        p -> {
          latch.countDown();
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    var mediaFile = mediaFileRepository.findFirstByFilepath(path.toAbsolutePath().toString());
    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should clean up in-flight map after processing")
  void shouldCleanUpInFlightMapAfterProcessing() throws Exception {
    var path = createFile("/media/movies/Movie (2024).mkv");
    var callCount = new AtomicInteger(0);

    stabilityCheckerRef.set(
        p -> {
          callCount.incrementAndGet();
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                watchingService.handleFileEvent(DirectoryChangeEvent.EventType.MODIFY, path);
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

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
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
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptedLatch.countDown();
            return false;
          }
          return false;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.DELETE, path);

    assertThat(interruptedLatch.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("Should remove from in-flight map on delete event")
  void shouldRemoveFromInFlightMapOnDeleteEvent() throws Exception {
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
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            }
            return false;
          }
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
    assertThat(firstEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.DELETE, path);
    firstBlockLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
              }
              return callCount.get() >= 2;
            });

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should clean up in-flight map when processing throws exception")
  void shouldCleanUpInFlightMapWhenProcessingThrowsException() throws Exception {
    var seriesLibrary =
        Library.builder()
            .name("TV Shows")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepath("/media/shows")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.SERIES)
            .build();
    libraryRepository.save(seriesLibrary);
    Files.createDirectories(fileSystem.getPath("/media/shows"));

    var path = createFile("/media/shows/Show S01E01 (2024).mkv");
    var callCount = new AtomicInteger(0);

    stabilityCheckerRef.set(
        p -> {
          callCount.incrementAndGet();
          return true;
        });

    watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              if (callCount.get() < 2) {
                watchingService.handleFileEvent(DirectoryChangeEvent.EventType.CREATE, path);
              }
              return callCount.get() >= 2;
            });

    assertThat(callCount.get()).isEqualTo(2);
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
