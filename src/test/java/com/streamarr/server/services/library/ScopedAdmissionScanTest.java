package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.LibraryScanProperties;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
import com.streamarr.server.fakes.FakeLibraryMutationTransaction;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.ArtworkServiceFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.CompanyService;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveFileAdmission;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveLimitAlgorithm;
import com.streamarr.server.services.library.admission.adaptive.ScopedAdaptiveFileAdmission;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.ExternalIdVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Throwaway: scan semantics of C1 (executor) and C1S (structured task scope) side by side. */
@Tag("UnitTest")
@DisplayName("POC scan semantics under C1 and C1S admission")
class ScopedAdmissionScanTest {

  enum Variant {
    C1,
    C1S
  }

  private final FakeLibraryRepository libraryRepository = new FakeLibraryRepository();
  private final FakeMediaFileRepository mediaFileRepository = new FakeMediaFileRepository();
  private final CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
  private final PersonService personService = mock(PersonService.class);
  private final MetadataProvider<Movie> tmdbMovieProvider = mock(TMDBMovieProvider.class);
  private final ArtworkService artworkService = artworkService();
  private final MovieService movieService =
      new MovieService(
          new FakeMovieRepository(),
          personService,
          mock(GenreService.class),
          mock(CompanyService.class),
          null,
          artworkService,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
  private final AdmissionRuntime runtime = new AdmissionRuntime();
  private final LibraryManagementService service =
      new LibraryManagementService(
          new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
          new VideoExtensionValidator(),
          new MovieFileProcessor(
              new DefaultVideoFileMetadataParser(),
              new ExternalIdVideoFileMetadataParser(),
              new MovieMetadataProviderResolver(List.of(tmdbMovieProvider)),
              movieService,
              mediaFileRepository,
              new MutexFactoryProvider()),
          mock(SeriesFileProcessor.class),
          libraryRepository,
          new FakeLibraryMetadataRepository(),
          mediaFileRepository,
          movieService,
          mock(SeriesService.class),
          eventPublisher,
          new MutexFactoryProvider(),
          mock(LibraryRefreshService.class),
          fileSystem,
          new FakeLibraryMutationTransaction(),
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          artworkService);

  private UUID libraryId;

  @BeforeEach
  void setUp() {
    libraryId = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary()).getId();
    when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  private AdaptiveFileAdmission use(Variant variant) {
    var registry = new SimpleMeterRegistry();
    var admission =
        switch (variant) {
          case C1 ->
              new AdaptiveFileAdmission(
                  AdaptiveLimitAlgorithm.GRADIENT2, runtime, Duration.ofMinutes(1), registry);
          case C1S ->
              new ScopedAdaptiveFileAdmission(
                  AdaptiveLimitAlgorithm.GRADIENT2, runtime, Duration.ofMinutes(1), registry);
        };
    service.useFileAdmission(admission);
    return admission;
  }

  private Path libraryRoot() throws IOException {
    var root =
        FilepathCodec.decode(
            fileSystem, libraryRepository.findById(libraryId).orElseThrow().getFilepathUri());
    return Files.createDirectories(root);
  }

  private static Path movieFile(Path root, String title) throws IOException {
    var folder = Files.createDirectory(root.resolve(title + " (2024)"));
    return Files.createFile(folder.resolve(title + " (2024).mkv"));
  }

  private MediaFileStatus statusOf(Path file) {
    return mediaFileRepository
        .findFirstByFilepathUri(FilepathCodec.encode(file))
        .orElseThrow()
        .getStatus();
  }

  @ParameterizedTest
  @EnumSource(Variant.class)
  @DisplayName("Should become unhealthy while every other file completes when one file fails")
  void shouldBecomeUnhealthyWhileEveryOtherFileCompletesWhenOneFileFails(Variant variant)
      throws Exception {
    use(variant);
    var root = libraryRoot();
    var failing = movieFile(root, "Failing Movie");
    var others = List.of(movieFile(root, "First Movie"), movieFile(root, "Second Movie"));
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenAnswer(
            invocation -> {
              var parsed = invocation.getArgument(0, VideoFileParserResult.class);
              if (parsed.title().equals("Failing Movie")) {
                throw new IllegalStateException("simulated search failure");
              }

              return new NotFound();
            });

    service.scanLibrary(libraryId);

    var library = libraryRepository.findById(libraryId).orElseThrow();
    assertThat(library.getStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(eventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
    assertThat(others).allMatch(file -> statusOf(file) == MediaFileStatus.METADATA_NOT_FOUND);
    assertThat(mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(failing)))
        .isPresent();
    assertThat(runtime.inFlight()).isZero();
  }

  @ParameterizedTest
  @EnumSource(Variant.class)
  @DisplayName(
      "Should restore the interrupt flag, cancel the running file and become unhealthy when the scan is interrupted")
  void shouldRestoreInterruptCancelRunningFileAndBecomeUnhealthyWhenScanIsInterrupted(
      Variant variant) throws Exception {
    var admission = use(variant);
    movieFile(libraryRoot(), "Interrupted Movie");
    var started = new CountDownLatch(1);
    var never = new CountDownLatch(1);
    var fileInterrupted = new AtomicBoolean();
    var interruptRestored = new AtomicBoolean();
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenAnswer(
            _ -> {
              started.countDown();
              try {
                never.await();
              } catch (InterruptedException e) {
                fileInterrupted.set(true);
                throw e;
              }

              return new NotFound();
            });

    var scan =
        Thread.ofPlatform()
            .start(
                () -> {
                  service.scanLibrary(libraryId);
                  interruptRestored.set(Thread.currentThread().isInterrupted());
                });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    scan.interrupt();

    assertThat(scan.join(Duration.ofSeconds(5))).isTrue();
    assertThat(interruptRestored).isTrue();
    assertThat(fileInterrupted).isTrue();
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(runtime.inFlight()).isZero();
    assertThat(admission.name()).startsWith("adaptive-gradient2");
  }

  /** Movie files by title, and the titles whose search ran or was interrupted. */
  private record StoppableLibrary(
      Map<String, Path> files, Set<String> searched, Set<String> interrupted) {}

  private StoppableLibrary stopDuringSearch(
      ScopedAdaptiveFileAdmission admission, int files, int stopAtSearch) throws Exception {
    var root = libraryRoot();
    var byTitle = new HashMap<String, Path>();
    for (var index = 0; index < files; index++) {
      var title = "Movie " + (char) ('A' + index);
      byTitle.put(title, movieFile(root, title));
    }

    Set<String> searched = ConcurrentHashMap.newKeySet();
    Set<String> interrupted = ConcurrentHashMap.newKeySet();
    var searches = new AtomicInteger();
    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenAnswer(
            invocation -> {
              var title = invocation.getArgument(0, VideoFileParserResult.class).title();
              searched.add(title);
              var stopping = searches.incrementAndGet() == stopAtSearch;
              if (stopping) {
                admission.requestStop();
              }

              try {
                // The stopping search outlasts the earlier ones, so it is still running when they
                // end.
                Thread.sleep(stopping ? 200 : 20);
              } catch (InterruptedException e) {
                interrupted.add(title);
                throw e;
              }

              return new NotFound();
            });
    return new StoppableLibrary(byTitle, searched, interrupted);
  }

  @Test
  @DisplayName(
      "Should become unhealthy with the stop as the scan failure and finish every started file when C1S is stopped with files left")
  void shouldBecomeUnhealthyAndFinishEveryStartedFileWhenScopedAdmissionIsStoppedWithFilesLeft()
      throws Exception {
    var admission = (ScopedAdaptiveFileAdmission) use(Variant.C1S);
    var library = stopDuringSearch(admission, 12, 3);

    service.scanLibrary(libraryId);

    System.out.printf(
        "[evidence] scan stopped with files left: searched %s, interrupted %s, status %s%n",
        library.searched(),
        library.interrupted(),
        libraryRepository.findById(libraryId).orElseThrow().getStatus());
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(eventPublisher.getEventsOfType(ScanCompletedEvent.class)).isEmpty();
    assertThat(library.searched()).hasSizeLessThan(12);
    assertThat(library.interrupted()).isEmpty();
    assertThat(library.searched())
        .allMatch(
            title -> statusOf(library.files().get(title)) == MediaFileStatus.METADATA_NOT_FOUND);
    assertThat(runtime.inFlight()).isZero();
  }

  @Test
  @DisplayName(
      "Should complete healthy with every file processed when a C1S stop lands after every file was admitted")
  void shouldCompleteHealthyWithEveryFileProcessedWhenScopedStopLandsAfterEveryFileWasAdmitted()
      throws Exception {
    var admission = (ScopedAdaptiveFileAdmission) use(Variant.C1S);
    var library = stopDuringSearch(admission, 3, 3);

    service.scanLibrary(libraryId);

    System.out.printf(
        "[evidence] scan stopped after the walk was admitted: searched %s, interrupted %s,"
            + " status %s%n",
        library.searched(),
        library.interrupted(),
        libraryRepository.findById(libraryId).orElseThrow().getStatus());
    assertThat(library.searched()).containsExactlyInAnyOrderElementsOf(library.files().keySet());
    assertThat(library.interrupted()).isEmpty();
    assertThat(library.files().values())
        .allMatch(file -> statusOf(file) == MediaFileStatus.METADATA_NOT_FOUND);
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
    assertThat(eventPublisher.getEventsOfType(ScanCompletedEvent.class)).hasSize(1);
    assertThat(runtime.inFlight()).isZero();
  }

  private static ArtworkService artworkService() {
    var downloader = new FakeTmdbHttpService();
    return ArtworkServiceFixture.artworkServiceBuilder()
        .imageRepository(new FakeImageRepository())
        .imageDownloader(downloader)
        .build();
  }
}
