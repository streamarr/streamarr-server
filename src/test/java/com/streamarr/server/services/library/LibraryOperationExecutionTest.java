package com.streamarr.server.services.library;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.LibraryScanProperties;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.CountingSleeper;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeLibraryMutationTransaction;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryOperationScheduling;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FixedLimitAdmissions;
import com.streamarr.server.fakes.GatedImageDownloader;
import com.streamarr.server.fakes.ThrowingFileSystemWrapper;
import com.streamarr.server.fixtures.ArtworkFixture;
import com.streamarr.server.fixtures.CreditServiceFixture;
import com.streamarr.server.fixtures.FileDiscoveryRunsFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.CreditService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.AdaptiveAdmissions;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.ExternalIdVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("Library Operation Execution Tests")
class LibraryOperationExecutionTest {

  private static final Duration SCAN_BOUND = Duration.ofSeconds(5);

  private final AuthenticatedIdentity identity = defaultIdentityBuilder().build();
  private final FakeLibraryMutationTransaction libraryMutationTransaction =
      new FakeLibraryMutationTransaction();

  private final CreditService creditService = CreditServiceFixture.creditServiceBuilder().build();
  private final MetadataProvider<Movie> tmdbMovieProvider = mock(TMDBMovieProvider.class);
  private final MovieMetadataProviderResolver fakeMovieMetadataProviderResolver =
      new MovieMetadataProviderResolver(List.of(tmdbMovieProvider));
  private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
  private Runnable beforeProbeSchedulingReadsMediaFile = () -> {};
  private Runnable beforeCountingFileStatuses = () -> {};
  private Consumer<MediaFile> beforeSavingMediaFile = _ -> {};
  private final MediaFileRepository fakeMediaFileRepository =
      new FakeMediaFileRepository() {
        @Override
        public <S extends MediaFile> S save(S mediaFile) {
          beforeSavingMediaFile.accept(mediaFile);
          return super.save(mediaFile);
        }

        @Override
        public Optional<MediaFile> findById(UUID id) {
          beforeProbeSchedulingReadsMediaFile.run();
          return super.findById(id);
        }

        @Override
        public Map<MediaFileStatus, Long> countStatuses(Collection<UUID> mediaFileIds) {
          beforeCountingFileStatuses.run();
          return super.countStatuses(mediaFileIds);
        }
      };
  private final MovieRepository fakeMovieRepository = new FakeMovieRepository();
  private final CapturingEventPublisher capturingEventPublisher = new CapturingEventPublisher();
  private final FakeTransactionManager transactionManager = new FakeTransactionManager();
  private final MutationTransactions mutationTransactions =
      new MutationTransactions(transactionManager, new ConstraintViolationTranslator());
  private final FakeImageRepository fakeImageRepository = new FakeImageRepository();
  private final ArtworkFixture artwork = artworkWith(fakeImageRepository);
  private final ArtworkRequestService artworkRequestService = artwork.artworkRequestService();
  private final MovieService movieService =
      new MovieService(
          fakeMovieRepository,
          creditService,
          null,
          artworkRequestService,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());

  private final MovieFileProcessor movieFileProcessor =
      new MovieFileProcessor(
          new DefaultVideoFileMetadataParser(),
          new ExternalIdVideoFileMetadataParser(),
          fakeMovieMetadataProviderResolver,
          movieService,
          fakeMediaFileRepository,
          new MutexFactoryProvider());

  private final SeriesFileProcessor seriesFileProcessor = mock(SeriesFileProcessor.class);
  private final SeriesService seriesService = mock(SeriesService.class);

  private final LibraryRefreshService libraryRefreshService = mock(LibraryRefreshService.class);

  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeProbeTaskRequests probeTaskRequests = succeedingProbeRequests(probeOutcomes);
  private final CountingSleeper probeCheckSleeper = new CountingSleeper();
  private final FileDiscoveryRuns fileDiscoveryRuns = fileDiscoveryRunsWith(artwork);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(fakeLibraryRepository);
  private final OperationRunner operations = serviceBuilder().build();

  private UUID savedLibraryId;

  @BeforeEach
  public void setup() {
    // Fixture uses plain paths because file:// URIs can't round-trip through Jimfs.
    var fakeLibrary = LibraryFixtureCreator.buildFakeLibrary();
    var savedLibrary = fakeLibraryRepository.save(fakeLibrary);

    savedLibraryId = savedLibrary.getId();
  }

  @AfterEach
  public void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should become unhealthy when file processing task fails during scan")
  void shouldBecomeUnhealthyWhenFileProcessingTaskFailsDuringScan() throws Exception {
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "Failing Movie", "Failing Movie (2024).mkv");

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenThrow(new RuntimeException("simulated search failure"));

    operations.scanLibrary(savedLibraryId);

    var library = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
    assertThat(library.getStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
    assertThat(fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath)))
        .as("Media file should have been created before scanLibrary returned")
        .isPresent();
    assertThat(probeTaskRequests.requests()).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should fail the scan and return every admission when every admitted file task throws")
  void shouldFailTheScanAndReturnEveryAdmissionWhenEveryAdmittedFileTaskThrows() throws Exception {
    var rootPath = createRootLibraryDirectory();
    createMovieFile(rootPath, "First Movie", "First Movie (2024).mkv");
    createMovieFile(rootPath, "Second Movie", "Second Movie (2024).mkv");
    failEverySearchOnceBothFilesSearch();

    operations.scanLibrary(savedLibraryId);

    assertThat(libraryStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
    assertThat(scanAdmissions()).isEqualTo(2);
    assertThat(scanReports("ignored")).isEqualTo(2);
  }

  @Test
  @DisplayName("Should report every failed file task when the file walk fails")
  void shouldReportEveryFailedFileTaskWhenTheFileWalkFails() throws Exception {
    var rootPath = createRootLibraryDirectory();
    createMovieFile(rootPath, "First Movie", "First Movie (2024).mkv");
    createMovieFile(rootPath, "Second Movie", "Second Movie (2024).mkv");
    failEverySearchOnceBothFilesSearch();
    var library = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
    var fileWalk = fileWalkBuilder().build();

    var failure = catchThrowable(() -> fileWalk.discoverFiles(library));

    assertThat(failure)
        .isInstanceOf(LibraryScanFailedException.class)
        .hasMessageStartingWith("2 file processing tasks failed");
    assertThat(failure.getSuppressed()).hasSize(1);
  }

  private void failEverySearchOnceBothFilesSearch() {
    var failuresReady = new CountDownLatch(2);
    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenAnswer(
            invocation -> {
              var parsedFile = invocation.getArgument(0, VideoFileParserResult.class);
              failuresReady.countDown();
              assertThat(failuresReady.await(5, TimeUnit.SECONDS)).isTrue();
              throw new IllegalStateException("simulated failure: " + parsedFile.title());
            });
  }

  @Test
  @DisplayName("Should finish submitted files when a file processing task fails")
  void shouldFinishSubmittedFilesWhenFileProcessingTaskFails() throws Exception {
    var rootPath = createRootLibraryDirectory();
    var failingMoviePath =
        createMovieFile(rootPath, "00 Failing Movie", "Failing Movie (2024).mkv");
    var completingMoviePath =
        createMovieFile(rootPath, "99 Completing Movie", "Completing Movie (2024).mkv");
    var completingTaskStarted = new CountDownLatch(1);
    var releaseCompletingTask = new CountDownLatch(1);
    var failingTaskFinished = new CountDownLatch(1);

    try (var paths = Files.walk(rootPath)) {
      assertThat(paths.filter(Files::isRegularFile).toList())
          .as("fixture requires the failing future to be awaited first")
          .containsExactly(failingMoviePath, completingMoviePath);
    }

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenAnswer(
            invocation -> {
              var parsedFile = invocation.getArgument(0, VideoFileParserResult.class);
              if (parsedFile.title().equals("Completing Movie")) {
                completingTaskStarted.countDown();
                releaseCompletingTask.await();
                return new NotFound();
              }

              assertThat(completingTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();
              failingTaskFinished.countDown();
              throw new RuntimeException("simulated search failure");
            });

    var scanThread = Thread.ofPlatform().start(() -> operations.scanLibrary(savedLibraryId));

    try {
      assertThat(failingTaskFinished.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(scanThread.isAlive())
          .as("scan should still be waiting for the completing task")
          .isTrue();
    } finally {
      releaseCompletingTask.countDown();
    }

    assertThat(scanThread.join(Duration.ofSeconds(5))).isTrue();
    assertThat(
            fakeMediaFileRepository
                .findFirstByFilepathUri(FilepathCodec.encode(completingMoviePath))
                .orElseThrow()
                .getStatus())
        .isEqualTo(MediaFileStatus.METADATA_NOT_FOUND);
  }

  @Test
  @DisplayName(
      "Should keep the library scanning with its operation stored when a shutdown interrupts the"
          + " scan during file processing")
  void shouldKeepTheLibraryScanningWhenAShutdownInterruptsTheScanDuringFileProcessing()
      throws Exception {
    var rootPath = createRootLibraryDirectory();
    createMovieFile(rootPath, "Interrupted Movie", "Interrupted Movie (2024).mkv");
    var processingStarted = new CountDownLatch(1);
    var releaseProcessing = new CountDownLatch(1);
    var interruptRestored = new AtomicBoolean();
    var workerInterrupted = new AtomicBoolean();
    var result = new AtomicReference<LibraryOperationExecutionResult>();

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenAnswer(
            _ -> {
              processingStarted.countDown();
              try {
                releaseProcessing.await();
              } catch (InterruptedException exception) {
                workerInterrupted.set(true);
                throw exception;
              }

              return new NotFound();
            });

    var scanThread =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  result.set(operations.scanLibrary(savedLibraryId));
                  interruptRestored.set(Thread.currentThread().isInterrupted());
                });
    scanThread.start();

    try {
      assertThat(processingStarted.await(5, TimeUnit.SECONDS)).isTrue();
      scanThread.interrupt();
      assertThat(scanThread.join(Duration.ofSeconds(5))).isTrue();
    } finally {
      releaseProcessing.countDown();
    }

    assertThat(result).hasValue(new LibraryOperationExecutionResult.Interrupted());
    assertThat(interruptRestored).isTrue();
    assertThat(workerInterrupted).isTrue();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.SCANNING);
    assertThat(libraryOperations.libraryOperations()).hasSize(1);
    assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
    assertThat(capturingEventPublisher.getEventsOfType(ScanEndedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should set library status to unhealthy when the library filepath cannot be accessed")
  void shouldSetLibraryStatusToUnhealthyWhenLibraryFilepathInaccessible() {
    operations.scanLibrary(savedLibraryId);

    assertThat(fakeLibraryRepository.findById(savedLibraryId)).isPresent();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
  }

  @Test
  @DisplayName(
      "Should set library status to unhealthy when file walk throws UncheckedIOException during iteration")
  void shouldSetLibraryStatusToUnhealthyWhenFileWalkThrowsUncheckedIOException()
      throws IOException {
    var rootPath = createRootLibraryDirectory();
    createMovieFile(rootPath, "About Time", "About Time (2013).mkv");

    var throwingFileSystem = new ThrowingFileSystemWrapper(fileSystem);

    var serviceWithThrowingFs = serviceWith(throwingFileSystem);

    serviceWithThrowingFs.scanLibrary(savedLibraryId);

    assertThat(fakeLibraryRepository.findById(savedLibraryId)).isPresent();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
  }

  @Test
  @DisplayName(
      "Should set library status to unhealthy when file walk throws SecurityException during iteration")
  void shouldSetLibraryStatusToUnhealthyWhenFileWalkThrowsSecurityException() throws IOException {
    var rootPath = createRootLibraryDirectory();
    createMovieFile(rootPath, "About Time", "About Time (2013).mkv");

    var throwingFileSystem =
        new ThrowingFileSystemWrapper(
            fileSystem,
            () -> new SecurityException("Simulated security manager denial during traversal"));

    var serviceWithThrowingFs = serviceWith(throwingFileSystem);

    serviceWithThrowingFs.scanLibrary(savedLibraryId);

    assertThat(fakeLibraryRepository.findById(savedLibraryId)).isPresent();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
  }

  @Test
  @DisplayName("Should set library status to healthy when the library filepath can be accessed")
  void shouldSetLibraryStatusToHealthyWhenLibraryFilepathAccessible() throws IOException {
    createRootLibraryDirectory();

    operations.scanLibrary(savedLibraryId);

    assertThat(fakeLibraryRepository.findById(savedLibraryId)).isPresent();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName(
      "Should publish ScanCompletedEvent with correct library ID when scan completes successfully")
  void shouldPublishScanCompletedEventWhenScanSucceeds() throws IOException {
    createRootLibraryDirectory();

    operations.scanLibrary(savedLibraryId);

    var events = capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().libraryId()).isEqualTo(savedLibraryId);
  }

  @Test
  @DisplayName("Should not publish ScanCompletedEvent when library path is inaccessible")
  void shouldNotPublishScanCompletedEventWhenLibraryPathInaccessible() {
    operations.scanLibrary(savedLibraryId);

    var events = capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName("Should not publish ScanCompletedEvent when file walk throws SecurityException")
  void shouldNotPublishScanCompletedEventWhenFileWalkThrowsSecurityException() throws IOException {
    var rootPath = createRootLibraryDirectory();
    createMovieFile(rootPath, "About Time", "About Time (2013).mkv");

    var throwingFileSystem =
        new ThrowingFileSystemWrapper(
            fileSystem,
            () -> new SecurityException("Simulated security manager denial during traversal"));

    var serviceWithThrowingFs = serviceWith(throwingFileSystem);

    serviceWithThrowingFs.scanLibrary(savedLibraryId);

    var events = capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName(
      "Should publish ScanEndedEvent with correct library ID when scan completes successfully")
  void shouldPublishScanEndedEventWhenScanSucceeds() throws IOException {
    createRootLibraryDirectory();

    operations.scanLibrary(savedLibraryId);

    var events = capturingEventPublisher.getEventsOfType(ScanEndedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().libraryId()).isEqualTo(savedLibraryId);
  }

  @Test
  @DisplayName("Should publish ScanEndedEvent when library path is inaccessible")
  void shouldPublishScanEndedEventWhenLibraryPathInaccessible() {
    operations.scanLibrary(savedLibraryId);

    var events = capturingEventPublisher.getEventsOfType(ScanEndedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().libraryId()).isEqualTo(savedLibraryId);
  }

  @Test
  @DisplayName(
      "Should skip creating a media file when provided a library containing an unsupported file extension")
  void shouldSkipCreatingMediaFileWhenProvidedLibraryContainingUnsupportedMovie()
      throws IOException {
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "About Time", "About Time (2013).av1");

    operations.scanLibrary(savedLibraryId);

    var mediaFile = fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath));

    assertThat(mediaFile).isEmpty();
  }

  @Test
  @DisplayName("Should match media file when filename contains external ID tag")
  void shouldMatchMediaFileWhenFilenameContainsExternalIdTag() throws IOException {
    var movieFolder = "Inception (2010) [imdb-tt1375666]";
    var movieFilename = "Inception (2010) [imdb-tt1375666].mkv";

    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title("Inception")
                    .externalId("27205")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(
                    Movie.builder().title("Inception").build(), List.of(), Map.of(), Map.of())));

    operations.scanLibrary(savedLibraryId);

    var mediaFile = fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath));

    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
  }

  @Test
  @DisplayName(
      "Should skip updating metadata when provided a media file that has already been matched")
  void shouldSkipUpdatingMetadataWhenProvidedMediaFileThatHasBeenMatched() throws IOException {
    var movieFolder = "About Time";
    var movieFilename = "About Time (2013).mkv";

    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

    var mediaFileBeforeRefresh =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(savedLibraryId)
                .filepathUri(FilepathCodec.encode(moviePath))
                .filename(movieFilename)
                .status(MediaFileStatus.MATCHED)
                .build());

    operations.scanLibrary(savedLibraryId);

    var mediaFileAfterRefresh =
        fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath));

    assertThat(mediaFileAfterRefresh).contains(mediaFileBeforeRefresh);
  }

  @Test
  @DisplayName("Should request probe work when scanning an already matched file")
  void shouldRequestProbeWorkWhenScanningAlreadyMatchedFile() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var path = createMovieFile(rootPath, "About Time", "About Time (2013).mkv");
    var mediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(savedLibraryId)
                .filepathUri(FilepathCodec.encode(path))
                .filename(path.getFileName().toString())
                .status(MediaFileStatus.MATCHED)
                .build());

    operations.scanLibrary(savedLibraryId);

    assertThat(probeTaskRequests.requests())
        .singleElement()
        .satisfies(request -> assertThat(request.mediaFileId()).isEqualTo(mediaFile.getId()));
  }

  @Test
  @DisplayName("Should keep scanning when the probe request is still being saved")
  void shouldKeepScanningWhenTheProbeRequestIsStillBeingSaved() throws Exception {
    var rootPath = createRootLibraryDirectory();
    var path = createMovieFile(rootPath, "About Time", "About Time (2013).mkv");
    saveMatchedMediaFile(path);
    var enqueueStarted = new CountDownLatch(1);
    var enqueueReleased = new CountDownLatch(1);
    probeTaskRequests.dispatchWith(
        request -> {
          enqueueStarted.countDown();
          awaitEnqueueRelease(enqueueReleased);
          probeTaskRequests.succeed(request);
        });

    try (var scan = BoundedTask.start(() -> operations.scanLibrary(savedLibraryId))) {
      try {
        assertThat(enqueueStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(libraryStatus()).isEqualTo(LibraryStatus.SCANNING);
        assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
      } finally {
        enqueueReleased.countDown();
      }

      scan.await(SCAN_BOUND);
    }

    assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).hasSize(1);
    assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should fail the scan when required probe enqueue fails")
  void shouldFailScanWhenRequiredProbeEnqueueFails() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var path = createMovieFile(rootPath, "About Time", "About Time (2013).mkv");
    saveMatchedMediaFile(path);
    probeTaskRequests.dispatchWith(
        _ -> {
          throw new DataAccessResourceFailureException("Queue unavailable");
        });

    operations.scanLibrary(savedLibraryId);

    assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
  }

  @Test
  @DisplayName(
      "Should complete a scan when one source disappears after enumeration before probe scheduling")
  void shouldCompleteScanWhenSourceDisappearsAfterEnumerationBeforeProbeScheduling()
      throws Exception {
    var rootPath = createRootLibraryDirectory();
    var path = createMovieFile(rootPath, "About Time", "About Time (2013).mkv");
    saveMatchedMediaFile(path);
    beforeProbeSchedulingReadsMediaFile =
        () -> {
          try {
            Files.deleteIfExists(path);
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        };
    probeTaskRequests.dispatchWith(
        request -> probeOutcomes.withdrawProbeRequest(request.mediaFileId()));

    operations.scanLibrary(savedLibraryId);

    assertThat(Files.exists(path)).isFalse();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
    assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should finish the scan when a source becomes unreadable after the library is listed")
  void shouldFinishTheScanWhenASourceBecomesUnreadableAfterTheLibraryIsListed() throws Exception {
    var rootPath = createRootLibraryDirectory();
    var path = createMovieFile(rootPath, "Unavailable mount", "movie.mkv");
    saveMatchedMediaFile(path);
    beforeProbeSchedulingReadsMediaFile =
        () -> {
          try {
            if (!Files.isSymbolicLink(path)) {
              Files.delete(path);
              Files.createSymbolicLink(path, path.getFileName());
            }
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        };
    probeTaskRequests.dispatchWith(
        request ->
            probeTaskRequests.fail(request, ItemFailureReason.SOURCE_INACCESSIBLE, Instant.now()));

    operations.scanLibrary(savedLibraryId);

    assertThat(Files.isSymbolicLink(path)).isTrue();
    assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
    assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).hasSize(1);
  }

  @Nested
  @DisplayName("Scan completion")
  class ScanCompletionTests {

    @Test
    @DisplayName("Should stay scanning when the requested probe has no outcome")
    void shouldStayScanningWhenTheRequestedProbeHasNoOutcome() throws Exception {
      saveMatchedMediaFile(createMovieFile(createRootLibraryDirectory(), "Held", "Held.mkv"));
      probeTaskRequests.dispatchWith(_ -> {});
      var whileWaiting = new AtomicReference<ScanState>();
      probeCheckSleeper.onSleep(
          _ -> {
            whileWaiting.compareAndSet(null, scanState());
            probeTaskRequests.succeed(probeTaskRequests.requests().getFirst());
          });

      scan(operations);

      assertThat(whileWaiting).hasValue(new ScanState(LibraryStatus.SCANNING, 0));
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
      assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("Should finish the scan when a probe attempt failure is recorded")
    void shouldFinishTheScanWhenAProbeAttemptFailureIsRecorded() throws Exception {
      saveMatchedMediaFile(createMovieFile(createRootLibraryDirectory(), "Share", "Share.mkv"));
      probeTaskRequests.dispatchWith(_ -> {});
      var whileWaiting = new AtomicReference<ScanState>();
      probeCheckSleeper.onSleep(
          _ -> {
            whileWaiting.compareAndSet(null, scanState());
            probeTaskRequests.fail(
                probeTaskRequests.requests().getFirst(),
                ItemFailureReason.SOURCE_INACCESSIBLE,
                Instant.now());
          });

      scan(operations);

      assertThat(whileWaiting).hasValue(new ScanState(LibraryStatus.SCANNING, 0));
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should stay scanning when required artwork is not saved")
    void shouldStayScanningWhenRequiredArtworkIsNotSaved() throws Exception {
      createMovieFile(createRootLibraryDirectory(), "About Time", "About Time (2013).mkv");
      var downloader = new GatedImageDownloader(createTestImage(600, 900));
      downloader.holdPathsStartingWith("/poster");
      var imageRepository = new FakeImageRepository();
      var service =
          libraryManagementServiceWithArtwork(
              ArtworkFixture.builder()
                  .imageRepository(imageRepository)
                  .imageDownloader(downloader)
                  .build());
      matchMovieWithPoster("About Time");

      try (var scan =
          BoundedTask.start(
              () -> {
                service.scanLibrary(savedLibraryId);
                return !imageRepository.findAll().isEmpty();
              })) {
        downloader.awaitHeldDownloads(1, Duration.ofSeconds(5));
        downloader.releaseHeldDownloads();

        assertThat(scan.await(SCAN_BOUND)).as("poster saved when the scan returned").isTrue();
      } finally {
        downloader.releaseHeldDownloads();
      }

      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName(
        "Should finish the scan healthy when a retried artwork step stores the result that failed to save")
    void shouldFinishTheScanHealthyWhenARetriedArtworkStepStoresTheResultThatFailedToSave()
        throws Exception {
      createMovieFile(createRootLibraryDirectory(), "About Time", "About Time (2013).mkv");
      var itemResults = new FakeItemResultRepository();
      itemResults.failArtworkResultWritesWith(
          new DataAccessResourceFailureException("database unavailable"), 1);
      var imageDownloader = new FakeTmdbHttpService();
      imageDownloader.setImageData(createTestImage(600, 900));
      var retried =
          ArtworkFixture.builder()
              .itemResults(itemResults)
              .imageDownloader(imageDownloader)
              .build();
      var service = libraryManagementServiceWithArtwork(retried);
      matchMovieWithPoster("About Time");

      try (var scan = BoundedTask.start(() -> service.scanLibrary(savedLibraryId))) {
        retried.scheduling().awaitUnfinishedAttempt(SCAN_BOUND);
        assertThat(libraryStatus()).isEqualTo(LibraryStatus.SCANNING);

        retried.scheduling().runPendingSteps();

        scan.await(SCAN_BOUND);
      }

      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
      assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("Should become unhealthy when the statuses of the scanned files cannot be read")
    void shouldBecomeUnhealthyWhenTheStatusesOfTheScannedFilesCannotBeRead() throws Exception {
      var path =
          createMovieFile(createRootLibraryDirectory(), "About Time", "About Time (2013).mkv");
      saveMatchedMediaFile(path);
      beforeCountingFileStatuses =
          () -> {
            throw new DataAccessResourceFailureException("database unavailable");
          };

      operations.scanLibrary(savedLibraryId);

      assertThat(libraryStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
      assertThat(capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
    }

    private void scan(OperationRunner service) throws Exception {
      BoundedTask.runWithin(SCAN_BOUND, () -> service.scanLibrary(savedLibraryId));
    }

    private ScanState scanState() {
      return new ScanState(
          libraryStatus(),
          capturingEventPublisher.getEventsOfType(ScanCompletedEvent.class).size());
    }
  }

  @Nested
  @DisplayName("Scan admission")
  class ScanAdmissionTests {

    @Test
    @DisplayName(
        "Should return every admission when file tasks match, skip a matched file, record a failure, time out and throw")
    void shouldReturnEveryAdmissionWhenFileTasksMatchSkipAMatchedFileRecordAFailureTimeOutAndThrow()
        throws Exception {
      var root = createRootLibraryDirectory();
      createMovieFile(root, "A Alpha", "Alpha (2001).mkv");
      saveMatchedMediaFile(createMovieFile(root, "B Bravo", "Bravo (2002).mkv"));
      createMovieFile(root, "C Charlie", "Charlie (2003).mkv");
      createMovieFile(root, "D Delta", "Delta (2004).mkv");
      createMovieFile(root, "E Echo", "Echo (2005).mkv");
      beforeSavingMediaFile =
          mediaFile -> {
            if (mediaFile.getFilename().startsWith("Echo")) {
              throw new DataAccessResourceFailureException("simulated media file failure");
            }
          };
      when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
      when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
          .thenAnswer(
              invocation ->
                  switch (invocation.getArgument(0, VideoFileParserResult.class).title()) {
                    case "Alpha" -> foundMovie("Alpha");
                    case "Delta" ->
                        new TemporarilyUnavailable(new HttpTimeoutException("search timed out"));
                    default -> new NotFound();
                  });
      when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
          .thenReturn(movieMetadata("Alpha"));

      scan();

      assertThat(scanAdmissions()).isEqualTo(5);
      assertThat(scanReports("latency")).isEqualTo(2);
      assertThat(scanReports("ignored")).isEqualTo(2);
      assertThat(scanReports("drop")).isEqualTo(1);
      assertThat(meterRegistry.find("streamarr.library.admission.in_flight").gauges()).isEmpty();
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("Should admit no further file and finish admitted files when a file task throws")
    void shouldAdmitNoFurtherFileAndFinishAdmittedFilesWhenAFileTaskThrows() throws Exception {
      var root = createRootLibraryDirectory();
      var heldPath = createMovieFile(root, "A Held", "Held (2001).mkv");
      createMovieFile(root, "B Failing", "Failing (2002).mkv");
      var neverPath = createMovieFile(root, "C Never", "Never (2003).mkv");
      var admissions = new FixedLimitAdmissions(2);
      var heldSearching = new CountDownLatch(1);
      var releaseHeld = new CountDownLatch(1);
      when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
      when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
          .thenAnswer(
              invocation -> {
                if (invocation.getArgument(0, VideoFileParserResult.class).title().equals("Held")) {
                  heldSearching.countDown();
                  assertThat(releaseHeld.await(5, TimeUnit.SECONDS)).isTrue();
                  return new NotFound();
                }

                assertThat(heldSearching.await(5, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("simulated search failure");
              });
      var service =
          serviceBuilder()
              .libraryFileWalk(
                  fileWalkBuilder().libraryOperationAdmissions(admissionsWith(admissions)).build())
              .build();

      try (var scan = BoundedTask.start(() -> service.scanLibrary(savedLibraryId))) {
        admissions.awaitReports(1, SCAN_BOUND);
        releaseHeld.countDown();
        scan.await(SCAN_BOUND);
      } finally {
        releaseHeld.countDown();
      }

      assertThat(mediaFileStatus(heldPath)).isEqualTo(MediaFileStatus.METADATA_NOT_FOUND);
      assertThat(fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(neverPath)))
          .isEmpty();
      assertThat(scanAdmissions()).isEqualTo(2);
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("Should finish the scan healthy when a file task records a matching failure")
    void shouldFinishTheScanHealthyWhenAFileTaskRecordsAMatchingFailure() throws Exception {
      var path = createMovieFile(createRootLibraryDirectory(), "Unknown", "Unknown (2001).mkv");
      when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
      when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(new NotFound());

      scan();

      assertThat(mediaFileStatus(path)).isEqualTo(MediaFileStatus.METADATA_NOT_FOUND);
      assertThat(scanReports("latency")).isEqualTo(1);
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should admit no task for a file with an unsupported extension")
    void shouldAdmitNoTaskForAFileWithAnUnsupportedExtension() throws Exception {
      var root = createRootLibraryDirectory();
      createMovieFile(root, "Notes", "notes.txt");
      saveMatchedMediaFile(createMovieFile(root, "Matched", "Matched (2001).mkv"));

      scan();

      assertThat(scanAdmissions()).isEqualTo(1);
      assertThat(allScanReports()).isEqualTo(1);
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should report an already matched file as ignored without reading artwork results")
    void shouldReportAnAlreadyMatchedFileAsIgnoredWithoutReadingArtworkResults() throws Exception {
      saveMatchedMediaFile(
          createMovieFile(createRootLibraryDirectory(), "Matched", "Matched (2001).mkv"));

      scan();

      assertThat(scanReports("ignored")).isEqualTo(1);
      assertThat(allScanReports()).isEqualTo(1);
      assertThat(artwork.requests().completionEvidenceReads()).isZero();
    }

    @Test
    @DisplayName("Should hold a file task's admission until its poster result is recorded")
    void shouldHoldAFileTasksAdmissionUntilItsPosterResultIsRecorded() throws Exception {
      createMovieFile(createRootLibraryDirectory(), "About Time", "About Time (2013).mkv");
      var resultCheckSleeper = new CountingSleeper();
      var imageDownloader = new FakeTmdbHttpService();
      imageDownloader.setImageData(createTestImage(600, 900));
      var heldArtwork =
          ArtworkFixture.builder()
              .imageDownloader(imageDownloader)
              .sleeper(resultCheckSleeper)
              .build();
      heldArtwork.scheduling().holdScheduledSteps();
      var inFlightWhilePosterPending = new AtomicReference<Double>();
      resultCheckSleeper.onSleep(
          _ -> {
            inFlightWhilePosterPending.compareAndSet(null, scanTasksInFlight());
            heldArtwork.scheduling().runPendingSteps();
          });
      var service = libraryManagementServiceWithArtwork(heldArtwork);
      matchMovieWithPoster("About Time");

      BoundedTask.runWithin(SCAN_BOUND, () -> service.scanLibrary(savedLibraryId));

      assertThat(inFlightWhilePosterPending).hasValue(1.0);
      assertThat(heldArtwork.scheduling().pendingTargets()).isEmpty();
      assertThat(scanReports("latency")).isEqualTo(1);
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName(
        "Should match every file and finish healthy when a file task cannot read its artwork results")
    void shouldMatchEveryFileAndFinishHealthyWhenAFileTaskCannotReadItsArtworkResults()
        throws Exception {
      var root = createRootLibraryDirectory();
      var firstPath = createMovieFile(root, "A First", "About Time (2013).mkv");
      var secondPath = createMovieFile(root, "B Second", "About Time (2013) Remux.mkv");
      var imageDownloader = new FakeTmdbHttpService();
      imageDownloader.setImageData(createTestImage(600, 900));
      var posterArtwork = ArtworkFixture.builder().imageDownloader(imageDownloader).build();
      posterArtwork
          .requests()
          .failNextCompletionEvidenceReadWith(
              new CannotGetJdbcConnectionException(
                  "simulated pool timeout",
                  new SQLTransientConnectionException("connection not available")));
      var service = libraryManagementServiceWithArtwork(posterArtwork);
      matchMovieWithPoster("About Time");

      BoundedTask.runWithin(SCAN_BOUND, () -> service.scanLibrary(savedLibraryId));

      assertThat(mediaFileStatus(firstPath)).isEqualTo(MediaFileStatus.MATCHED);
      assertThat(mediaFileStatus(secondPath)).isEqualTo(MediaFileStatus.MATCHED);
      assertThat(scanReports("drop")).isEqualTo(1);
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    private double scanTasksInFlight() {
      return meterRegistry
          .get("streamarr.library.admission.in_flight")
          .tag("operation", "scan")
          .gauge()
          .value();
    }

    private void scan() throws Exception {
      BoundedTask.runWithin(SCAN_BOUND, () -> operations.scanLibrary(savedLibraryId));
    }

    private MediaFileStatus mediaFileStatus(Path path) {
      return fakeMediaFileRepository
          .findFirstByFilepathUri(FilepathCodec.encode(path))
          .orElseThrow()
          .getStatus();
    }

    private static Found foundMovie(String title) {
      return new Found(
          RemoteSearchResult.builder()
              .title(title)
              .externalId(title.toLowerCase())
              .externalSourceType(ExternalSourceType.TMDB)
              .build());
    }

    private static MetadataFetchOutcome<MetadataResult<Movie>> movieMetadata(String title) {
      return new MetadataFetchOutcome.Found<>(
          MetadataFixture.<Movie>metadataResultBuilder()
              .entity(Movie.builder().title(title).titleSort(title).build())
              .build());
    }
  }

  private void matchMovieWithPoster(String title) {
    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title(title)
                    .externalId("123")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));
    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                MetadataFixture.<Movie>metadataResultBuilder()
                    .entity(Movie.builder().title(title).titleSort(title).build())
                    .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
                    .build()));
  }

  private record ScanState(LibraryStatus status, int completedEvents) {}

  private LibraryStatus libraryStatus() {
    return fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus();
  }

  private OperationRunner libraryManagementServiceWithArtwork(ArtworkFixture artwork) {
    var artworkMovieService =
        new MovieService(
            fakeMovieRepository,
            creditService,
            null,
            artwork.artworkRequestService(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var artworkFileDiscoveryRuns = fileDiscoveryRunsWith(artwork);
    return serviceBuilder()
        .libraryFileWalk(
            fileWalkBuilder()
                .movieFileProcessor(
                    new MovieFileProcessor(
                        new DefaultVideoFileMetadataParser(),
                        new ExternalIdVideoFileMetadataParser(),
                        fakeMovieMetadataProviderResolver,
                        artworkMovieService,
                        fakeMediaFileRepository,
                        new MutexFactoryProvider()))
                .fileDiscoveryRuns(artworkFileDiscoveryRuns)
                .artworkRunWaiter(artwork.artworkRunWaiter())
                .build())
        .fileDiscoveryRuns(artworkFileDiscoveryRuns)
        .build();
  }

  private void saveMatchedMediaFile(Path path) {
    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(savedLibraryId)
            .filepathUri(FilepathCodec.encode(path))
            .filename(path.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .build());
  }

  private static void awaitEnqueueRelease(CountDownLatch release) {
    try {
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  @Test
  @DisplayName(
      "Should match media file when provided a library containing a file with a supported extension")
  void shouldMatchMediaFileWhenProvidedLibraryContainingSupportedMovie() throws IOException {
    var movieFolder = "About Time";
    var movieFilename = "About Time (2013).mkv";

    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(
                RemoteSearchResult.builder()
                    .title(movieFolder)
                    .externalId("123")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(
                    Movie.builder().title(movieFolder).build(), List.of(), Map.of(), Map.of())));

    operations.scanLibrary(savedLibraryId);

    var mediaFile = fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath));

    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
  }

  @ParameterizedTest(name = "Should match existing retryable media file when status is {0}")
  @EnumSource(
      value = MediaFileStatus.class,
      names = {"UNMATCHED", "METADATA_NOT_FOUND", "METADATA_UNAVAILABLE"})
  @DisplayName("Should match existing retryable media file when status is retryable")
  void shouldMatchExistingRetryableMediaFileWhenStatusIsRetryable(MediaFileStatus retryableStatus)
      throws IOException {
    var movieFolder = "About Time";
    var movieFilename = "About Time (2013).mkv";

    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

    var mediaFileBeforeRefresh =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(savedLibraryId)
                .filepathUri(FilepathCodec.encode(moviePath))
                .filename(movieFilename)
                .status(retryableStatus)
                .build());

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            new Found(RemoteSearchResult.builder().title(movieFolder).externalId("123").build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(Movie.builder().build(), List.of(), Map.of(), Map.of())));

    operations.scanLibrary(savedLibraryId);

    var mediaFileAfterRefresh =
        fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath));

    assertThat(mediaFileAfterRefresh).containsSame(mediaFileBeforeRefresh);
    assertThat(mediaFileAfterRefresh.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
  }

  @Test
  @DisplayName("Should mark metadata unavailable when TMDB search is temporarily unavailable")
  void shouldMarkMetadataUnavailableWhenTmdbSearchIsTemporarilyUnavailable() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "Cop Land", "Cop Land (1997).mkv");
    var timeout = new IOException("Connection timed out");

    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(new TemporarilyUnavailable(timeout));

    operations.scanLibrary(savedLibraryId);

    assertThat(fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath)))
        .get()
        .extracting(MediaFile::getStatus)
        .isEqualTo(MediaFileStatus.METADATA_UNAVAILABLE);
  }

  @Test
  @DisplayName("Should not attempt cleanup when library path is inaccessible")
  void shouldNotAttemptCleanupWhenLibraryPathInaccessible() {
    var orphanedMediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(savedLibraryId)
                .filepathUri("/library/nonexistent/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    operations.scanLibrary(savedLibraryId);

    assertThat(fakeMediaFileRepository.findById(orphanedMediaFile.getId())).isPresent();
  }

  @Test
  @DisplayName(
      "Should skip processing when provided a library containing a system file with a valid extension")
  void shouldSkipProcessingWhenGivenLibraryContainingSystemFile() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var systemFilePath = createMovieFile(rootPath, "About Time", "._About Time (2013).mkv");

    operations.scanLibrary(savedLibraryId);

    var mediaFile =
        fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(systemFilePath));

    assertThat(mediaFile).isEmpty();
  }

  @Nested
  @DisplayName("Refresh Library Tests")
  class RefreshLibraryTests {

    @Test
    @DisplayName("Should transition library to HEALTHY when refresh succeeds")
    void shouldTransitionToHealthyWhenRefreshSucceeds() {
      operations.refreshLibrary(savedLibraryId);

      var library = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
      assertThat(library.getStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should refresh metadata and propagate artwork mode when top-level refresh runs")
    void shouldRefreshMetadataAndPropagateArtworkModeWhenTopLevelRefreshRuns() {
      var library = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
      var movie =
          fakeMovieRepository.save(
              Movie.builder().title("Original").titleSort("original").library(library).build());
      var stored =
          fakeImageRepository.save(
              imageBuilder(movie.getId()).key("/poster.jpg").path("movie/poster").build());
      movie
          .getExternalIds()
          .add(
              ExternalIdentifier.builder()
                  .externalSourceType(ExternalSourceType.TMDB)
                  .externalId("27205")
                  .build());
      when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
      when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
          .thenReturn(
              new MetadataFetchOutcome.Found<>(
                  MetadataFixture.<Movie>metadataResultBuilder()
                      .entity(Movie.builder().title("Refreshed").titleSort("refreshed").build())
                      .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
                      .build()));
      var refreshService =
          LibraryRefreshService.builder()
              .seriesRepository(new FakeSeriesRepository())
              .movieRepository(fakeMovieRepository)
              .seriesService(seriesService)
              .movieService(movieService)
              .seriesMetadataProviderResolver(mock(SeriesMetadataProviderResolver.class))
              .movieMetadataProviderResolver(fakeMovieMetadataProviderResolver)
              .artworkRequestService(artworkRequestService)
              .artworkRunWaiter(artwork.artworkRunWaiter())
              .itemResults(new FakeItemResultRepository())
              .clock(Clock.systemUTC())
              .libraryOperationAdmissions(admissionsWith(new AdaptiveAdmissions()))
              .build();
      var service = libraryManagementServiceWithRefreshService(refreshService);

      service.refreshLibrary(savedLibraryId, ImageRefreshMode.FORCE_REFRESH);

      assertThat(fakeMovieRepository.findById(movie.getId()).orElseThrow().getTitle())
          .isEqualTo("Refreshed");
      assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus())
          .isEqualTo(LibraryStatus.HEALTHY);
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          fakeImageRepository.findByEntityIdAndEntityType(
                              movie.getId(), ImageEntityType.MOVIE))
                      .extracting(Image::getId)
                      .isNotEmpty()
                      .doesNotContain(stored.getId()));
    }

    @Test
    @DisplayName("Should transition library to UNHEALTHY when refresh fails")
    void shouldTransitionToUnhealthyWhenRefreshFails() throws Exception {
      doThrow(new RuntimeException("simulated refresh failure"))
          .when(libraryRefreshService)
          .refreshLibrary(any(Library.class), any(ImageRefreshMode.class));

      operations.refreshLibrary(savedLibraryId);

      var library = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
      assertThat(library.getStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("Should publish RefreshEndedEvent when refresh succeeds")
    void shouldPublishRefreshEndedEventWhenRefreshSucceeds() {
      operations.refreshLibrary(savedLibraryId);

      var events = capturingEventPublisher.getEventsOfType(RefreshEndedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().libraryId()).isEqualTo(savedLibraryId);
    }

    @Test
    @DisplayName("Should publish RefreshEndedEvent when refresh fails")
    void shouldPublishRefreshEndedEventWhenRefreshFails() throws Exception {
      doThrow(new RuntimeException("simulated refresh failure"))
          .when(libraryRefreshService)
          .refreshLibrary(any(Library.class), any(ImageRefreshMode.class));

      operations.refreshLibrary(savedLibraryId);

      var events = capturingEventPublisher.getEventsOfType(RefreshEndedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().libraryId()).isEqualTo(savedLibraryId);
    }

    @Test
    @DisplayName("Should transition from UNHEALTHY to HEALTHY when refresh succeeds")
    void shouldTransitionFromUnhealthyToHealthyWhenRefreshSucceeds() {
      var library = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
      library.setStatus(LibraryStatus.UNHEALTHY);
      fakeLibraryRepository.save(library);

      operations.refreshLibrary(savedLibraryId);

      var updated = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
      assertThat(updated.getStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should pass the operation's image refresh mode to the refresh")
    void shouldPassTheOperationsImageRefreshModeToTheRefresh() {
      var recordingRefreshService = new RecordingLibraryRefreshService();
      var service = libraryManagementServiceWithRefreshService(recordingRefreshService);

      service.refreshLibrary(savedLibraryId, ImageRefreshMode.REFRESH_IF_CHANGED);

      assertThat(recordingRefreshService.imageRefreshMode())
          .isEqualTo(ImageRefreshMode.REFRESH_IF_CHANGED);
      assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getStatus())
          .isEqualTo(LibraryStatus.HEALTHY);
      assertThat(capturingEventPublisher.getEventsOfType(RefreshEndedEvent.class))
          .singleElement()
          .extracting(RefreshEndedEvent::libraryId)
          .isEqualTo(savedLibraryId);
    }
  }

  @Nested
  @DisplayName("Library Status Change Tests")
  class LibraryStatusChangeTests {

    private final StatusObservingEventPublisher statusObserver =
        new StatusObservingEventPublisher(fakeLibraryRepository);
    private final OperationRunner service = serviceBuilder().eventPublisher(statusObserver).build();

    @Test
    @DisplayName("Should publish a status change after each saved status when a scan succeeds")
    void shouldPublishStatusChangeAfterEachSavedStatusWhenScanSucceeds() throws IOException {
      createRootLibraryDirectory();

      service.scanLibrary(savedLibraryId);

      assertThat(statusObserver.observedChanges())
          .containsExactly(
              new ObservedStatus(savedLibraryId, LibraryStatus.SCANNING),
              new ObservedStatus(savedLibraryId, LibraryStatus.HEALTHY));
    }

    @Test
    @DisplayName("Should publish an UNHEALTHY status change when a scan fails")
    void shouldPublishUnhealthyStatusChangeWhenScanFails() {
      service.scanLibrary(savedLibraryId);

      assertThat(statusObserver.observedChanges())
          .containsExactly(
              new ObservedStatus(savedLibraryId, LibraryStatus.SCANNING),
              new ObservedStatus(savedLibraryId, LibraryStatus.UNHEALTHY));
    }

    @Test
    @DisplayName("Should publish REFRESHING then HEALTHY status changes when a refresh succeeds")
    void shouldPublishRefreshingThenHealthyStatusChangesWhenRefreshSucceeds() {
      service.refreshLibrary(savedLibraryId);

      assertThat(statusObserver.observedChanges())
          .containsExactly(
              new ObservedStatus(savedLibraryId, LibraryStatus.REFRESHING),
              new ObservedStatus(savedLibraryId, LibraryStatus.HEALTHY));
    }

    @Test
    @DisplayName("Should publish an UNHEALTHY status change when a refresh fails")
    void shouldPublishUnhealthyStatusChangeWhenRefreshFails() throws Exception {
      doThrow(new RuntimeException("simulated refresh failure"))
          .when(libraryRefreshService)
          .refreshLibrary(any(Library.class), any(ImageRefreshMode.class));

      service.refreshLibrary(savedLibraryId);

      assertThat(statusObserver.observedChanges())
          .containsExactly(
              new ObservedStatus(savedLibraryId, LibraryStatus.REFRESHING),
              new ObservedStatus(savedLibraryId, LibraryStatus.UNHEALTHY));
    }
  }

  @Nested
  @DisplayName("Execution lifecycle")
  class ExecutionLifecycleTests {

    @Test
    @DisplayName("Should end and change nothing when the operation no longer exists")
    void shouldEndAndChangeNothingWhenTheOperationNoLongerExists() {
      var result = operations.execution().execute(UUID.randomUUID());

      assertThat(result).isEqualTo(new LibraryOperationExecutionResult.Ended());
      assertThat(libraryStatus()).isEqualTo(LibraryStatus.HEALTHY);
      assertThat(capturingEventPublisher.getEventsOfType(ScanEndedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName(
        "Should delete the operation and publish the end of the scan when the scan finishes")
    void shouldDeleteTheOperationAndPublishTheEndOfTheScanWhenTheScanFinishes() throws IOException {
      createRootLibraryDirectory();

      var result = operations.scanLibrary(savedLibraryId);

      assertThat(result).isEqualTo(new LibraryOperationExecutionResult.Ended());
      assertThat(libraryOperations.libraryOperations()).isEmpty();
      assertThat(fakeLibraryRepository.findById(savedLibraryId).orElseThrow().getScanCompletedOn())
          .isNotNull();
      assertThat(capturingEventPublisher.getEventsOfType(ScanEndedEvent.class))
          .containsExactly(new ScanEndedEvent(savedLibraryId));
      assertThat(executions(LibraryOperationKind.SCAN, "ended")).isOne();
      assertThat(durations(LibraryOperationKind.SCAN, LibraryOperationConclusion.FINISHED)).isOne();
    }

    @Test
    @DisplayName(
        "Should leave the library busy and count the failed end when the end cannot be saved")
    void shouldLeaveTheLibraryBusyAndCountTheFailedEndWhenTheEndCannotBeSaved() throws IOException {
      createRootLibraryDirectory();
      var failure = new DataAccessResourceFailureException("simulated connection loss");
      var failingRuns =
          serviceBuilder()
              .executionLibraryOperations(
                  new FakeLibraryOperationRepository(fakeLibraryRepository) {
                    @Override
                    public Optional<LibraryOperation> findLibraryOperation(UUID id) {
                      return libraryOperations.findLibraryOperation(id);
                    }

                    @Override
                    public boolean tryEndLibraryOperation(LibraryOperationEnd end) {
                      throw failure;
                    }
                  })
              .build();
      failingRuns.service().startLibraryScan(identity, savedLibraryId);
      var libraryOperationId = failingRuns.scheduling().scheduledExecutions().getLast();

      assertThatThrownBy(() -> failingRuns.execution().execute(libraryOperationId))
          .isSameAs(failure);

      assertThat(libraryStatus()).isEqualTo(LibraryStatus.SCANNING);
      assertThat(libraryOperations.libraryOperations()).hasSize(1);
      assertThat(capturingEventPublisher.getEventsOfType(ScanEndedEvent.class)).isEmpty();
      assertThat(executions(LibraryOperationKind.SCAN, "end_failed")).isOne();
    }

    @Test
    @DisplayName(
        "Should keep the operation stored when a refresh fails on a thread a shutdown interrupted")
    void shouldKeepTheOperationStoredWhenARefreshFailsOnAThreadAShutdownInterrupted()
        throws Exception {
      doAnswer(
              _ -> {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "the connection pool refused an interrupted thread");
              })
          .when(libraryRefreshService)
          .refreshLibrary(any(Library.class), any(ImageRefreshMode.class));

      try {
        var result = operations.refreshLibrary(savedLibraryId);

        assertThat(result).isEqualTo(new LibraryOperationExecutionResult.Interrupted());
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }

      assertThat(libraryStatus()).isEqualTo(LibraryStatus.REFRESHING);
      assertThat(libraryOperations.libraryOperations()).hasSize(1);
      assertThat(executions(LibraryOperationKind.REFRESH, "interrupted")).isOne();
    }

    private double executions(LibraryOperationKind kind, String result) {
      var counter =
          meterRegistry
              .find("streamarr.library.operations.executions")
              .tag("kind", kind.tagValue())
              .tag("result", result)
              .counter();
      if (counter == null) {
        return 0;
      }

      return counter.count();
    }

    private long durations(LibraryOperationKind kind, LibraryOperationConclusion conclusion) {
      var timer =
          meterRegistry
              .find("streamarr.library.operations.duration")
              .tag("kind", kind.tagValue())
              .tag("conclusion", conclusion.name().toLowerCase(Locale.ROOT))
              .timer();
      if (timer == null) {
        return 0;
      }

      return timer.count();
    }
  }

  private record ObservedStatus(UUID libraryId, LibraryStatus status) {}

  // Reads the saved status at the moment each status change is published.
  private static final class StatusObservingEventPublisher extends CapturingEventPublisher {

    private final LibraryRepository libraries;
    private final List<ObservedStatus> observedChanges = new CopyOnWriteArrayList<>();

    private StatusObservingEventPublisher(LibraryRepository libraries) {
      this.libraries = libraries;
    }

    @Override
    public void publishEvent(Object event) {
      super.publishEvent(event);
      if (event instanceof LibraryStatusChangedEvent(var libraryId)) {
        var status = libraries.findById(libraryId).orElseThrow().getStatus();
        observedChanges.add(new ObservedStatus(libraryId, status));
      }
    }

    private List<ObservedStatus> observedChanges() {
      return observedChanges;
    }
  }

  private static final class RecordingLibraryRefreshService extends LibraryRefreshService {

    private final AtomicReference<ImageRefreshMode> imageRefreshMode = new AtomicReference<>();

    private RecordingLibraryRefreshService() {
      super(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public void refreshLibrary(Library library, ImageRefreshMode requestedImageRefreshMode) {
      imageRefreshMode.set(requestedImageRefreshMode);
    }

    private ImageRefreshMode imageRefreshMode() {
      return imageRefreshMode.get();
    }
  }

  private Path createRootLibraryDirectory() throws IOException {
    var library = fakeLibraryRepository.findById(savedLibraryId);

    var path = FilepathCodec.decode(fileSystem, library.orElseThrow().getFilepathUri());
    Files.createDirectories(path);

    return path;
  }

  private static FakeProbeTaskRequests succeedingProbeRequests(
      FakeMediaFileContainerInfoRepository outcomes) {
    var requests = new FakeProbeTaskRequests(outcomes);
    requests.succeedEachRequest();
    return requests;
  }

  private FileDiscoveryRuns fileDiscoveryRunsWith(ArtworkFixture artwork) {
    return FileDiscoveryRunsFixture.fileDiscoveryRunsBuilder()
        .artwork(artwork)
        .mediaFiles(fakeMediaFileRepository)
        .outcomes(probeOutcomes)
        .probeTaskRequests(probeTaskRequests)
        .fileSystem(fileSystem)
        .sleeper(probeCheckSleeper)
        .build();
  }

  private static ArtworkFixture artworkWith(FakeImageRepository imageRepository) {
    var imageDownloader = new FakeTmdbHttpService();
    imageDownloader.setImageData(createTestImage(600, 900));
    return ArtworkFixture.builder()
        .imageRepository(imageRepository)
        .imageDownloader(imageDownloader)
        .build();
  }

  private RunnerBuilder serviceBuilder() {
    return new RunnerBuilder();
  }

  /**
   * Builds the services that start an operation and execute it, so a test can scan or refresh in
   * one call. Each builder setter replaces one collaborator of the execution.
   */
  private final class RunnerBuilder {

    private LibraryFileWalk libraryFileWalk = fileWalkBuilder().build();
    private FileDiscoveryRuns runs = fileDiscoveryRuns;
    private LibraryFolderCheck folderCheck = libraryFolderCheckWith(fileSystem);
    private LibraryRefreshService refreshService = libraryRefreshService;
    private ApplicationEventPublisher publisher = capturingEventPublisher;
    private LibraryOperationRepository executionLibraryOperations = libraryOperations;

    private RunnerBuilder executionLibraryOperations(LibraryOperationRepository repository) {
      this.executionLibraryOperations = repository;
      return this;
    }

    private RunnerBuilder libraryFileWalk(LibraryFileWalk libraryFileWalk) {
      this.libraryFileWalk = libraryFileWalk;
      return this;
    }

    private RunnerBuilder fileDiscoveryRuns(FileDiscoveryRuns runs) {
      this.runs = runs;
      return this;
    }

    private RunnerBuilder libraryFolderCheck(LibraryFolderCheck folderCheck) {
      this.folderCheck = folderCheck;
      return this;
    }

    private RunnerBuilder libraryRefreshService(LibraryRefreshService refreshService) {
      this.refreshService = refreshService;
      return this;
    }

    private RunnerBuilder eventPublisher(ApplicationEventPublisher publisher) {
      this.publisher = publisher;
      return this;
    }

    private OperationRunner build() {
      var metrics = new LibraryOperationMetrics(meterRegistry);
      var scheduling = new FakeLibraryOperationScheduling();
      var service =
          LibraryOperationService.builder()
              .libraryOperations(libraryOperations)
              .scheduling(scheduling)
              .mutationTransactions(mutationTransactions)
              .libraryMutationTransaction(libraryMutationTransaction)
              .eventPublisher(publisher)
              .metrics(metrics)
              .build();
      var execution =
          LibraryOperationExecution.builder()
              .libraryOperations(executionLibraryOperations)
              .libraryRepository(fakeLibraryRepository)
              .libraryFileWalk(libraryFileWalk)
              .fileDiscoveryRuns(runs)
              .libraryFolderCheck(folderCheck)
              .libraryRefreshService(refreshService)
              .eventPublisher(publisher)
              .transactionManager(transactionManager)
              .clock(Clock.systemUTC())
              .metrics(metrics)
              .build();
      return new OperationRunner(identity, service, scheduling, execution);
    }
  }

  /** Starts a scan or a refresh and runs its one execution on the calling thread. */
  private record OperationRunner(
      AuthenticatedIdentity identity,
      LibraryOperationService service,
      FakeLibraryOperationScheduling scheduling,
      LibraryOperationExecution execution) {

    LibraryOperationExecutionResult scanLibrary(UUID libraryId) {
      return execute(service.startLibraryScan(identity, libraryId));
    }

    LibraryOperationExecutionResult refreshLibrary(UUID libraryId) {
      return refreshLibrary(libraryId, ImageRefreshMode.PRESERVE);
    }

    LibraryOperationExecutionResult refreshLibrary(
        UUID libraryId, ImageRefreshMode imageRefreshMode) {
      return execute(service.startLibraryRefresh(identity, libraryId, imageRefreshMode));
    }

    private LibraryOperationExecutionResult execute(
        Outcome<Library, LibraryOperationRejection> started) {
      assertThat(started).isInstanceOf(Outcome.Accepted.class);
      return execution.execute(scheduling.scheduledExecutions().getLast());
    }
  }

  private LibraryFileWalk.LibraryFileWalkBuilder fileWalkBuilder() {
    return LibraryFileWalk.builder()
        .ignoredFileValidator(new IgnoredFileValidator(new LibraryScanProperties(null, null, null)))
        .videoExtensionValidator(new VideoExtensionValidator())
        .movieFileProcessor(movieFileProcessor)
        .seriesFileProcessor(seriesFileProcessor)
        .mediaFileRepository(fakeMediaFileRepository)
        .fileSystem(fileSystem)
        .fileDiscoveryRuns(fileDiscoveryRuns)
        .mutexFactoryProvider(new MutexFactoryProvider())
        .libraryOperationAdmissions(admissionsWith(new AdaptiveAdmissions()))
        .artworkRunWaiter(artwork.artworkRunWaiter());
  }

  private LibraryOperationAdmissions admissionsWith(AdaptiveAdmissions admissions) {
    return LibraryOperationAdmissions.builder()
        .meterRegistry(meterRegistry)
        .admissions(admissions)
        .build();
  }

  private double scanAdmissions() {
    var admitted =
        meterRegistry
            .find("streamarr.library.admission.admitted")
            .tag("operation", "scan")
            .counter();
    if (admitted == null) {
      return 0;
    }

    return admitted.count();
  }

  private double scanReports(String report) {
    var reports =
        meterRegistry
            .find("streamarr.library.admission.reports")
            .tag("operation", "scan")
            .tag("report", report)
            .counter();
    if (reports == null) {
      return 0;
    }

    return reports.count();
  }

  private double allScanReports() {
    return scanReports("latency") + scanReports("ignored") + scanReports("drop");
  }

  private OperationRunner serviceWith(FileSystem alternateFileSystem) {
    return serviceBuilder()
        .libraryFileWalk(fileWalkBuilder().fileSystem(alternateFileSystem).build())
        .libraryFolderCheck(libraryFolderCheckWith(alternateFileSystem))
        .build();
  }

  private LibraryFolderCheck libraryFolderCheckWith(FileSystem libraryFileSystem) {
    return new LibraryFolderCheck(libraryFileSystem, fakeMediaFileRepository, meterRegistry);
  }

  private OperationRunner libraryManagementServiceWithRefreshService(
      LibraryRefreshService refreshService) {
    return serviceBuilder().libraryRefreshService(refreshService).build();
  }

  private Path createMovieFile(Path libraryRoot, String folder, String filename)
      throws IOException {
    var movieFolder = libraryRoot.resolve(folder);
    Files.createDirectory(movieFolder);
    var movieFile = movieFolder.resolve(filename);
    Files.createFile(movieFile);

    return movieFile;
  }
}
