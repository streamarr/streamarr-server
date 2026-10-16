package com.streamarr.server.services.library;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.LibraryScanProperties;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import com.streamarr.server.exceptions.LibraryOperationNotStartedException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.CountingSleeper;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
import com.streamarr.server.fakes.FakeLibraryMutationTransaction;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryOperationScheduling;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.RecordingMetadataProvider;
import com.streamarr.server.fakes.RecordingSeriesMetadataProvider;
import com.streamarr.server.fakes.SecurityExceptionFileSystem;
import com.streamarr.server.fixtures.ArtworkFixture;
import com.streamarr.server.fixtures.CreditServiceFixture;
import com.streamarr.server.fixtures.FileDiscoveryRunsFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.CreditService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.concurrency.AdaptiveAdmissions;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.parsers.show.EpisodePathMetadataParser;
import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import com.streamarr.server.services.parsers.show.SeriesFolderNameParser;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexFixtures;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.ExternalIdVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hibernate.exception.ConstraintViolationException;
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
import org.springframework.dao.DataIntegrityViolationException;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("Library Management Service Tests")
class LibraryManagementServiceTest {

  private static final Duration SCAN_BOUND = Duration.ofSeconds(5);

  private final AuthenticatedIdentity identity = defaultIdentityBuilder().build();
  private final FakeLibraryMutationTransaction libraryMutationTransaction =
      new FakeLibraryMutationTransaction();

  private final CreditService creditService = CreditServiceFixture.creditServiceBuilder().build();
  private final MetadataProvider<Movie> tmdbMovieProvider = mock(TMDBMovieProvider.class);
  private final MovieMetadataProviderResolver fakeMovieMetadataProviderResolver =
      new MovieMetadataProviderResolver(List.of(tmdbMovieProvider));
  private Consumer<Library> beforeDeletingLibrary = _ -> {};
  private final LibraryRepository fakeLibraryRepository =
      new FakeLibraryRepository() {
        @Override
        public void delete(Library library) {
          beforeDeletingLibrary.accept(library);
          super.delete(library);
        }
      };
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

  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeProbeTaskRequests probeTaskRequests = succeedingProbeRequests(probeOutcomes);
  private final CountingSleeper probeCheckSleeper = new CountingSleeper();
  private final FileDiscoveryRuns fileDiscoveryRuns = fileDiscoveryRunsWith(artwork);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final FakeLibraryOperationRepository libraryOperations =
      new FakeLibraryOperationRepository(fakeLibraryRepository);
  private final FakeLibraryOperationScheduling libraryOperationScheduling =
      new FakeLibraryOperationScheduling();
  private final LibraryOperationService libraryOperationService =
      LibraryOperationService.builder()
          .libraryOperations(libraryOperations)
          .scheduling(libraryOperationScheduling)
          .mutationTransactions(mutationTransactions)
          .libraryMutationTransaction(libraryMutationTransaction)
          .eventPublisher(capturingEventPublisher)
          .metrics(new LibraryOperationMetrics(meterRegistry))
          .build();

  private final LibraryManagementService libraryManagementService = serviceBuilder().build();

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
  @DisplayName("Should process discovered file when library exists")
  void shouldProcessDiscoveredFileWhenLibraryExists() throws IOException {
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
                    .externalId("456")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(
                    Movie.builder().title(movieFolder).build(), List.of(), Map.of(), Map.of())));

    libraryManagementService.processDiscoveredFile(savedLibraryId, moviePath);

    var mediaFile = fakeMediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(moviePath));

    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
  }

  @Test
  @DisplayName("Should use folder title when movie filename lacks title and year")
  void shouldUseFolderTitleWhenMovieFilenameLacksTitleAndYear() throws IOException {
    var expectedSearch =
        VideoFileParserResult.builder().title("Café Meridian").year("2001").build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Café Meridian")
            .externalId("1001")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    var service =
        libraryManagementServiceWith(movieFileProcessorWith(metadataProvider), seriesFileProcessor);
    var filepathUri = "file:///library/Caf%C3%A9%20Meridian%20(2001)/movie.mkv";
    var moviePath = pathWithDisplayName(filepathUri, "movie.mkv");

    service.processDiscoveredFile(savedLibraryId, moviePath);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
  }

  @Test
  @DisplayName("Should search with external ID from filepath URI when stored filename is mangled")
  void shouldSearchWithExternalIdFromFilepathUriWhenStoredFilenameIsMangled() {
    var expectedSearch =
        VideoFileParserResult.builder()
            .title("Iron Harbor")
            .year("2010")
            .externalId("tt7654321")
            .externalSource(ExternalSourceType.IMDB)
            .build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Iron Harbor")
            .externalId("1002")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    var service =
        libraryManagementServiceWith(movieFileProcessorWith(metadataProvider), seriesFileProcessor);
    var filepathUri =
        "file:///library/Iron%20Harbor%20(2010)/"
            + "Iron%20Harbor%20(2010)%20%5Bimdb-tt7654321%5D.mkv";
    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(savedLibraryId)
            .filepathUri(filepathUri)
            .filename("legacy-mangled-name.mkv")
            .status(MediaFileStatus.UNMATCHED)
            .build());
    var moviePath = Path.of(URI.create(filepathUri));

    service.processDiscoveredFile(savedLibraryId, moviePath);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
  }

  @Test
  @DisplayName("Should ignore resolution dimensions when searching movie metadata")
  void shouldIgnoreResolutionDimensionsWhenSearchingMovieMetadata() throws IOException {
    var expectedSearch = VideoFileParserResult.builder().title("Paper Comet").build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Paper Comet")
            .externalId("507329")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    var service =
        libraryManagementServiceWith(movieFileProcessorWith(metadataProvider), seriesFileProcessor);
    var filepathUri = "file:///library/Paper%20Comet/Paper%20Comet%20%5B1920x1080%5D.mkv";
    var moviePath = pathWithDisplayName(filepathUri, "Paper Comet [1920x1080].mkv");

    service.processDiscoveredFile(savedLibraryId, moviePath);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
  }

  @Test
  @DisplayName("Should remove Unicode dash suffix when searching movie metadata")
  void shouldRemoveUnicodeDashSuffixWhenSearchingMovieMetadata() throws IOException {
    var expectedSearch = VideoFileParserResult.builder().title("Quiet Alloy").year("2014").build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Quiet Alloy")
            .externalId("815339")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingMetadataProvider<Movie>();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    var service =
        libraryManagementServiceWith(movieFileProcessorWith(metadataProvider), seriesFileProcessor);
    var filepathUri =
        "file:///library/Quiet%20Alloy/Quiet%20Alloy%20%E2%80%93%202014%20%E2%80%93%20WEBDL-1080p.mkv";
    var moviePath = pathWithDisplayName(filepathUri, "Quiet Alloy – 2014 – WEBDL-1080p.mkv");

    service.processDiscoveredFile(savedLibraryId, moviePath);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
  }

  @Test
  @DisplayName("Should search with URI-derived series metadata when processing root season file")
  void shouldSearchWithUriDerivedSeriesMetadataWhenProcessingRootSeasonFile() throws IOException {
    var library = fakeLibraryRepository.save(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var expectedSearch = VideoFileParserResult.builder().title("Harbor Relay").build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Harbor Relay")
            .externalId("2001")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingSeriesMetadataProvider();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    when(seriesService.findByTmdbId("2001")).thenReturn(Optional.empty());
    var service =
        libraryManagementServiceWith(movieFileProcessor, seriesFileProcessorWith(metadataProvider));
    var seriesPath =
        pathWithDisplayName(
            "file:///Season%2025/Harbor%20Relay.S25E09.mkv", "Harbor Relay.S25E09.mkv");

    service.processDiscoveredFile(library.getId(), seriesPath);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
  }

  @Test
  @DisplayName(
      "Should search with accented series folder title when filepath URI has a season folder")
  void shouldSearchWithAccentedSeriesFolderTitleWhenFilepathUriHasSeasonFolder()
      throws IOException {
    var library = fakeLibraryRepository.save(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var expectedSearch = VideoFileParserResult.builder().title("Lumière Harbor").build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Lumière Harbor")
            .externalId("2002")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var metadataProvider = new RecordingSeriesMetadataProvider();
    metadataProvider.willReturnSearchResultFor(expectedSearch, searchResult);
    when(seriesService.findByTmdbId("2002")).thenReturn(Optional.empty());
    var service =
        libraryManagementServiceWith(movieFileProcessor, seriesFileProcessorWith(metadataProvider));
    var filepathUri =
        "file:///library/Lumi%C3%A8re%20Harbor/S%C3%A6son%203/" + "Lumiere.Harbor.S03E05.mkv";
    var seriesPath = pathWithDisplayName(filepathUri, "Lumiere.Harbor.S03E05.mkv");

    service.processDiscoveredFile(library.getId(), seriesPath);

    assertThat(metadataProvider.searchRequests()).containsExactly(expectedSearch);
  }

  @Test
  @DisplayName("Should persist filename from filepath URI when Path display text is mangled")
  void shouldPersistFilenameFromFilepathUriWhenPathDisplayTextIsMangled() throws IOException {
    var movieFilename = "Café Meridian (2006) - [BLURAY-1080p][DTS 5.1].mkv";
    var filepathUri =
        "file:///library/Caf%C3%A9%20Meridian%20(2006)/"
            + "Caf%C3%A9%20Meridian%20(2006)%20-%20%5BBLURAY-1080p%5D%5BDTS%205.1%5D.mkv";
    var mangledFilename = "Caf�� Meridian (2006) - [BLURAY-1080p][DTS 5.1].mkv";
    var moviePath = pathWithDisplayName(filepathUri, mangledFilename);

    libraryManagementService.processDiscoveredFile(savedLibraryId, moviePath);

    assertThat(fakeMediaFileRepository.findFirstByFilepathUri(filepathUri))
        .get()
        .extracting(MediaFile::getFilename)
        .isEqualTo(movieFilename);
  }

  @Test
  @DisplayName("Should repair existing mangled filename from filepath URI when rescanned")
  void shouldRepairExistingMangledFilenameFromFilepathUriWhenRescanned() throws IOException {
    var movieFilename = "Café Meridian (2006) - [BLURAY-1080p][DTS 5.1].mkv";
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "Café Meridian (2006)", movieFilename);
    var filepathUri = FilepathCodec.encode(moviePath);

    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(savedLibraryId)
            .filepathUri(filepathUri)
            .filename("Caf�� Meridian (2006) - [BLURAY-1080p][DTS 5.1].mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    libraryManagementService.processDiscoveredFile(savedLibraryId, moviePath);

    var repairedMediaFile = fakeMediaFileRepository.findFirstByFilepathUri(filepathUri);
    assertThat(repairedMediaFile).get().extracting(MediaFile::getFilename).isEqualTo(movieFilename);
  }

  @Test
  @DisplayName("Should throw when library not found for discovered file")
  void shouldThrowWhenLibraryNotFoundForDiscoveredFile() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "Test", "Test (2024).mkv");

    var nonExistentId = UUID.randomUUID();

    assertThrows(
        LibraryNotFoundException.class,
        () -> libraryManagementService.processDiscoveredFile(nonExistentId, moviePath));
  }

  @Test
  @DisplayName("Should not publish item processed event when file is already matched")
  void shouldNotPublishItemProcessedEventWhenFileAlreadyMatched() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "Already Matched", "Already Matched (2024).mkv");

    fakeMediaFileRepository.save(
        MediaFile.builder()
            .libraryId(savedLibraryId)
            .filepathUri(FilepathCodec.encode(moviePath))
            .filename("Already Matched (2024).mkv")
            .status(MediaFileStatus.MATCHED)
            .build());

    libraryManagementService.processDiscoveredFile(savedLibraryId, moviePath);

    var events = capturingEventPublisher.getEventsOfType(ItemProcessedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName("Should create only one MediaFile when concurrent calls probe same filepath")
  void shouldCreateOnlyOneMediaFileWhenConcurrentCallsProbeSameFilepath() throws Exception {
    var rootPath = createRootLibraryDirectory();
    var path = createMovieFile(rootPath, "Concurrent Test", "Concurrent Test (2024).mkv");
    var barrier = new CyclicBarrier(2);
    BoundedTask.Action discover =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          libraryManagementService.processDiscoveredFile(savedLibraryId, path);
        };

    try (var first = BoundedTask.start(discover);
        var second = BoundedTask.start(discover)) {
      first.await(SCAN_BOUND);
      second.await(SCAN_BOUND);
    }

    assertThat(fakeMediaFileRepository.findByLibraryId(savedLibraryId))
        .as("Expected exactly one MediaFile for the same filepath")
        .hasSize(1);
  }

  @Test
  @DisplayName("Should throw IllegalStateException when library has unsupported media type")
  void shouldThrowIllegalStateExceptionWhenLibraryHasUnsupportedMediaType() throws IOException {
    var otherTypeLibrary =
        fakeLibraryRepository.save(
            Library.builder()
                .name("Other Type Library")
                .backend(LibraryBackend.LOCAL)
                .status(LibraryStatus.HEALTHY)
                .filepathUri("file:///library/" + UUID.randomUUID())
                .externalAgentStrategy(ExternalAgentStrategy.TMDB)
                .type(MediaType.OTHER)
                .build());

    var libraryPath = FilepathCodec.decode(fileSystem, otherTypeLibrary.getFilepathUri());
    Files.createDirectories(libraryPath);
    var movieFolder = libraryPath.resolve("Test Movie");
    Files.createDirectory(movieFolder);
    var movieFile = movieFolder.resolve("Test Movie (2024).mkv");
    Files.createFile(movieFile);

    var libraryId = otherTypeLibrary.getId();

    assertThrows(
        IllegalStateException.class,
        () -> libraryManagementService.processDiscoveredFile(libraryId, movieFile));
  }

  @Nested
  @DisplayName("Add Library Tests")
  class AddLibraryTests {

    @Test
    @DisplayName("Should reject with PathRequired when filepath is null")
    void shouldRejectWithPathRequiredWhenFilepathIsNull() {
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Test Library")
              .filepathUri(null)
              .build();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathRequired()));
    }

    @Test
    @DisplayName("Should reject with PathRequired when filepath is blank")
    void shouldRejectWithPathRequiredWhenFilepathIsBlank() {
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Test Library")
              .filepathUri("   ")
              .build();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathRequired()));
    }

    @Test
    @DisplayName("Should report every rejection when both name and path are missing")
    void shouldReportEveryRejectionWhenBothNameAndPathAreMissing() {
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder().name(" ").filepathUri(" ").build();
      var librariesBefore = fakeLibraryRepository.count();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(
              Outcome.rejected(
                  List.of(
                      new AddLibraryRejection.NameRequired(),
                      new AddLibraryRejection.PathRequired())));
      assertThat(fakeLibraryRepository.count()).isEqualTo(librariesBefore);
    }

    @Test
    @DisplayName("Should reject with NameRequired when the name is blank")
    void shouldRejectWithNameRequiredWhenNameIsBlank() throws IOException {
      var libraryPath = fileSystem.getPath("/unnamed-library");
      Files.createDirectories(libraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("")
              .filepathUri(libraryPath.toString())
              .build();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.NameRequired()));
    }

    @Test
    @DisplayName("Should reject with NameRequired when the name is null")
    void shouldRejectWithNameRequiredWhenNameIsNull() throws IOException {
      var libraryPath = fileSystem.getPath("/null-name-library");
      Files.createDirectories(libraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name(null)
              .filepathUri(libraryPath.toString())
              .build();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.NameRequired()));
    }

    @Test
    @DisplayName(
        "Should reject with PathAlreadyRegistered naming the library when another library uses"
            + " the path")
    void shouldRejectWithPathAlreadyRegisteredNamingLibraryWhenAnotherLibraryUsesPath()
        throws IOException {
      var libraryPath = fileSystem.getPath("/duplicate-library");
      Files.createDirectories(libraryPath);
      var firstLibrary =
          accepted(
              libraryManagementService.addLibrary(
                  identity,
                  LibraryFixtureCreator.unsavedLibraryBuilder()
                      .name("First Library")
                      .filepathUri(libraryPath.toString())
                      .build()));
      var duplicateLibrary =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Duplicate Library")
              .filepathUri(libraryPath.toString())
              .build();
      var librariesBefore = fakeLibraryRepository.count();
      var eventsBefore = capturingEventPublisher.getEventsOfType(LibraryAddedEvent.class).size();

      var outcome = libraryManagementService.addLibrary(identity, duplicateLibrary);

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(
                  new AddLibraryRejection.PathAlreadyRegistered(firstLibrary, libraryPath)));
      // Refused inside the unit, which rolls back: one commit (the first add), one rollback.
      assertThat(transactionManager.commits()).isEqualTo(1);
      assertThat(transactionManager.rollbacks()).isEqualTo(1);
      assertThat(fakeLibraryRepository.count()).isEqualTo(librariesBefore);
      assertThat(capturingEventPublisher.getEventsOfType(LibraryAddedEvent.class))
          .as("the refused add never reaches its publish")
          .hasSize(eventsBefore);
    }

    @Test
    @DisplayName("Should reject with PathAlreadyRegistered when paths are lexically equivalent")
    void shouldRejectWithPathAlreadyRegisteredWhenPathsAreLexicallyEquivalent() throws IOException {
      var libraryPath = fileSystem.getPath("/equivalent-library");
      Files.createDirectories(libraryPath);
      var firstLibrary =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("First Library")
              .filepathUri(libraryPath.toString())
              .build();
      var duplicateLibrary =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Duplicate Library")
              .filepathUri(libraryPath.resolve(".").toString())
              .build();
      var registered = accepted(libraryManagementService.addLibrary(identity, firstLibrary));

      assertThat(libraryManagementService.addLibrary(identity, duplicateLibrary))
          .isEqualTo(
              Outcome.rejected(
                  new AddLibraryRejection.PathAlreadyRegistered(registered, libraryPath)));
    }

    @Test
    @DisplayName(
        "Should reject with an INSIDE overlap when the path is inside another library's path")
    void shouldRejectWithInsideOverlapWhenPathIsInsideAnotherLibrarysPath() throws IOException {
      var movies = registeredLibrary("Movies", "/media/movies");
      var action = Files.createDirectories(fileSystem.getPath("/media/movies/action"));

      var outcome = libraryManagementService.addLibrary(identity, unsavedLibraryAt(action));

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(
                  new AddLibraryRejection.PathOverlap(
                      LibraryPathOverlap.INSIDE, movies, fileSystem.getPath("/media/movies"))));
    }

    @Test
    @DisplayName(
        "Should reject with a CONTAINS overlap when the path contains another library's path")
    void shouldRejectWithContainsOverlapWhenPathContainsAnotherLibrarysPath() throws IOException {
      var movies = registeredLibrary("Movies", "/media/movies");

      var outcome =
          libraryManagementService.addLibrary(
              identity, unsavedLibraryAt(fileSystem.getPath("/media")));

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(
                  new AddLibraryRejection.PathOverlap(
                      LibraryPathOverlap.CONTAINS, movies, fileSystem.getPath("/media/movies"))));
    }

    @Test
    @DisplayName(
        "Should name every contained library in name order when the path contains several"
            + " libraries' paths")
    void shouldNameEveryContainedLibraryInNameOrderWhenPathContainsSeveralLibrariesPaths()
        throws IOException {
      var television = registeredLibrary("Television", "/media/tv");
      var movies = registeredLibrary("Movies", "/media/movies");

      var outcome =
          libraryManagementService.addLibrary(
              identity, unsavedLibraryAt(fileSystem.getPath("/media")));

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(
                  List.of(
                      new AddLibraryRejection.PathOverlap(
                          LibraryPathOverlap.CONTAINS, movies, fileSystem.getPath("/media/movies")),
                      new AddLibraryRejection.PathOverlap(
                          LibraryPathOverlap.CONTAINS,
                          television,
                          fileSystem.getPath("/media/tv")))));
    }

    @Test
    @DisplayName(
        "Should accept a path when it shares only a name prefix with another library's path")
    void shouldAcceptPathWhenItSharesOnlyNamePrefixWithAnotherLibrarysPath() throws IOException {
      registeredLibrary("Movies", "/media/movies");
      var movies2 = Files.createDirectories(fileSystem.getPath("/media/movies2"));

      var outcome = libraryManagementService.addLibrary(identity, unsavedLibraryAt(movies2));

      assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    }

    @Test
    @DisplayName("Should accept a path when it names the same folders under another root")
    void shouldAcceptPathWhenItNamesSameFoldersUnderAnotherRoot() throws IOException {
      // The shared fixture library has a Unix path, which a Windows file system cannot read.
      fakeLibraryRepository.deleteById(savedLibraryId);
      var twoDrives = Configuration.windows().toBuilder().setRoots("C:\\", "D:\\").build();
      try (var twoDriveFileSystem = Jimfs.newFileSystem(twoDrives)) {
        Files.createDirectories(twoDriveFileSystem.getPath("C:\\media\\movies"));
        Files.createDirectories(twoDriveFileSystem.getPath("D:\\media\\movies"));
        var service = serviceWith(twoDriveFileSystem);
        accepted(service.addLibrary(identity, libraryNamed("Movies", "C:\\media\\movies")));

        var outcome = service.addLibrary(identity, libraryNamed("Films", "D:\\media\\movies"));

        assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
      }
    }

    @Test
    @DisplayName(
        "Should reject with an INSIDE overlap when the path reaches the library through dot"
            + " segments and a trailing separator")
    void shouldRejectWithInsideOverlapWhenPathReachesLibraryThroughDotSegmentsAndTrailingSeparator()
        throws IOException {
      var movies = registeredLibrary("Movies", "/media/movies");
      Files.createDirectories(fileSystem.getPath("/media/tv"));
      Files.createDirectories(fileSystem.getPath("/media/movies/action"));
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Action")
              .filepathUri("/media/tv/.././movies/action/")
              .build();

      var outcome = libraryManagementService.addLibrary(identity, library);

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(
                  new AddLibraryRejection.PathOverlap(
                      LibraryPathOverlap.INSIDE, movies, fileSystem.getPath("/media/movies"))));
    }

    @Test
    @DisplayName(
        "Should store no library, publish no event and schedule no scan when the path overlaps"
            + " another library's path")
    void shouldStoreNoLibraryPublishNoEventAndScheduleNoScanWhenPathOverlapsAnotherLibrarysPath()
        throws IOException {
      registeredLibrary("Movies", "/media/movies");
      var action = Files.createDirectories(fileSystem.getPath("/media/movies/action"));
      var librariesBefore = fakeLibraryRepository.count();
      var eventsBefore = capturingEventPublisher.getEventsOfType(LibraryAddedEvent.class).size();
      var scansBefore = libraryOperationScheduling.scheduledExecutions().size();

      libraryManagementService.addLibrary(identity, unsavedLibraryAt(action));

      assertThat(fakeLibraryRepository.count()).isEqualTo(librariesBefore);
      assertThat(capturingEventPublisher.getEventsOfType(LibraryAddedEvent.class))
          .hasSize(eventsBefore);
      assertThat(libraryOperationScheduling.scheduledExecutions()).hasSize(scansBefore);
      assertThat(transactionManager.rollbacks()).isEqualTo(1);
    }

    @Test
    @DisplayName(
        "Should reject with PathAlreadyRegistered when the path differs only in letter case on a"
            + " file system that ignores letter case")
    void
        shouldRejectWithPathAlreadyRegisteredWhenPathDiffersOnlyInLetterCaseOnCaseIgnoringFileSystem()
            throws IOException {
      try (var caseIgnoringFileSystem = Jimfs.newFileSystem(Configuration.osX())) {
        Files.createDirectories(caseIgnoringFileSystem.getPath("/media/Movies"));
        var service = serviceWith(caseIgnoringFileSystem);
        // The registered spelling differs from the folder's spelling on disk.
        var registered =
            accepted(service.addLibrary(identity, libraryNamed("Movies", "/media/movies")));

        var outcome = service.addLibrary(identity, libraryNamed("Films", "/MEDIA/MOVIES"));

        assertThat(outcome)
            .isEqualTo(
                Outcome.rejected(
                    new AddLibraryRejection.PathAlreadyRegistered(
                        registered, caseIgnoringFileSystem.getPath("/media/movies"))));
      }
    }

    @Test
    @DisplayName(
        "Should reject with an INSIDE overlap when the path differs in letter case on a file"
            + " system that ignores letter case")
    void shouldRejectWithInsideOverlapWhenPathDiffersInLetterCaseOnCaseIgnoringFileSystem()
        throws IOException {
      try (var caseIgnoringFileSystem = Jimfs.newFileSystem(Configuration.osX())) {
        Files.createDirectories(caseIgnoringFileSystem.getPath("/media/Movies/2024"));
        var service = serviceWith(caseIgnoringFileSystem);
        var registered =
            accepted(service.addLibrary(identity, libraryNamed("Movies", "/media/Movies")));

        // The deepest name has no letter, so the letter-case check reads the folder above it.
        var outcome = service.addLibrary(identity, libraryNamed("2024", "/MEDIA/movies/2024"));

        assertThat(outcome)
            .isEqualTo(
                Outcome.rejected(
                    new AddLibraryRejection.PathOverlap(
                        LibraryPathOverlap.INSIDE,
                        registered,
                        caseIgnoringFileSystem.getPath("/media/Movies"))));
      }
    }

    @Test
    @DisplayName(
        "Should store the path as given when the file system ignores letter case, so scans keep"
            + " the spelling the source root uses")
    void shouldStorePathAsGivenWhenFileSystemIgnoresLetterCase() throws IOException {
      try (var caseIgnoringFileSystem = Jimfs.newFileSystem(Configuration.osX())) {
        Files.createDirectories(caseIgnoringFileSystem.getPath("/Media/Movies"));

        var saved =
            accepted(
                serviceWith(caseIgnoringFileSystem)
                    .addLibrary(identity, libraryNamed("Movies", "/media/movies")));

        assertThat(saved.getFilepathUri())
            .isEqualTo(FilepathCodec.encode(caseIgnoringFileSystem.getPath("/media/movies")));
      }
    }

    @Test
    @DisplayName(
        "Should accept a path that differs only in letter case when the file system respects"
            + " letter case")
    void shouldAcceptPathThatDiffersOnlyInLetterCaseWhenFileSystemRespectsLetterCase()
        throws IOException {
      Files.createDirectories(fileSystem.getPath("/media/Movies"));
      Files.createDirectories(fileSystem.getPath("/media/movies"));
      accepted(
          libraryManagementService.addLibrary(identity, libraryNamed("Movies", "/media/Movies")));

      var outcome =
          libraryManagementService.addLibrary(identity, libraryNamed("Films", "/media/movies"));

      assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    }

    @Test
    @DisplayName("Should reject with PathNotFound when path does not exist on disk")
    void shouldRejectWithPathNotFoundWhenPathDoesNotExist() {
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Test Library")
              .filepathUri("/nonexistent/path")
              .build();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathNotFound()));
    }

    @Test
    @DisplayName("Should reject with PathNotFound when the path cannot be parsed")
    void shouldRejectWithPathNotFoundWhenPathCannotBeParsed() {
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Test Library")
              .filepathUri("bad\u0000path")
              .build();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathNotFound()));
    }

    @Test
    @DisplayName("Should reject with PathNotDirectory when path is a file")
    void shouldRejectWithPathNotDirectoryWhenPathIsFile() throws IOException {
      var filePath = fileSystem.getPath("/library/file.txt");
      Files.createDirectories(filePath.getParent());
      Files.createFile(filePath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Test Library")
              .filepathUri(filePath.toString())
              .build();

      assertThat(libraryManagementService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathNotDirectory()));
    }

    @Test
    @DisplayName("Should reject with PathNotReadable when the filesystem denies access")
    void shouldRejectWithPathNotReadableWhenFilesystemDeniesAccess() {
      var deniedService = serviceWith(new SecurityExceptionFileSystem(fileSystem));
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Test Library")
              .filepathUri("/denied")
              .build();

      assertThat(deniedService.addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathNotReadable()));
    }

    @Test
    @DisplayName("Should reject with PathNotReadable when the directory is not readable")
    void shouldRejectWithPathNotReadableWhenDirectoryIsNotReadable() throws IOException {
      var unreadablePath = fileSystem.getPath("/unreadable-library");
      Files.createDirectories(unreadablePath);
      var unreadableFileSystem = SecurityExceptionFileSystem.reportingUnreadable(fileSystem);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Unreadable Library")
              .filepathUri("/unreadable-library")
              .build();

      assertThat(serviceWith(unreadableFileSystem).addLibrary(identity, library))
          .isEqualTo(Outcome.rejected(new AddLibraryRejection.PathNotReadable()));
    }

    @Test
    @DisplayName("Should save library and return a generated ID when input is valid")
    void shouldSaveLibraryAndReturnGeneratedIdWhenInputIsValid() throws IOException {
      var newLibraryPath = fileSystem.getPath("/new-library");
      Files.createDirectories(newLibraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("New Library")
              .filepathUri(newLibraryPath.toString())
              .build();

      var savedLibrary = accepted(libraryManagementService.addLibrary(identity, library));

      assertThat(savedLibrary.getId()).isNotNull();
      assertThat(fakeLibraryRepository.findById(savedLibrary.getId())).isPresent();
      assertThat(transactionManager.commits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should authorize add library with the authenticated identity")
    void shouldAuthorizeAddLibraryWithAuthenticatedIdentity() throws IOException {
      var newLibraryPath = fileSystem.getPath("/authorized-library");
      Files.createDirectories(newLibraryPath);
      var library =
          LibraryFixtureCreator.buildUnsavedLibrary(
              "Authorized Library", newLibraryPath.toString());

      libraryManagementService.addLibrary(identity, library);

      assertThat(libraryMutationTransaction.executions())
          .singleElement()
          .satisfies(
              execution -> {
                assertThat(execution.identity()).isEqualTo(identity);
                assertThat(execution.intent()).isEqualTo(new Intent.AddLibrary());
              });
    }

    @Test
    @DisplayName("Should store filepathUri as encoded URI when adding library")
    void shouldStoreFilepathUriAsEncodedUriWhenAddingLibrary() throws IOException {
      var newLibraryPath = fileSystem.getPath("/encoded-library");
      Files.createDirectories(newLibraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Encoded Library")
              .filepathUri(newLibraryPath.toString())
              .build();

      var savedLibrary = accepted(libraryManagementService.addLibrary(identity, library));

      var persisted = fakeLibraryRepository.findById(savedLibrary.getId()).orElseThrow();
      assertThat(persisted.getFilepathUri()).startsWith("jimfs://");
    }

    @Test
    @DisplayName("Should return the library SCANNING with its first scan scheduled when added")
    void shouldReturnTheLibraryScanningWithItsFirstScanScheduledWhenAdded() throws IOException {
      var newLibraryPath = fileSystem.getPath("/new-library");
      Files.createDirectories(newLibraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("New Library")
              .filepathUri(newLibraryPath.toString())
              .build();

      var savedLibrary = accepted(libraryManagementService.addLibrary(identity, library));

      assertThat(savedLibrary.getStatus()).isEqualTo(LibraryStatus.SCANNING);
      assertThat(fakeLibraryRepository.findById(savedLibrary.getId()).orElseThrow().getStatus())
          .isEqualTo(LibraryStatus.SCANNING);
      var firstScan = libraryOperations.operationFor(savedLibrary.getId()).orElseThrow();
      assertThat(firstScan.kind()).isEqualTo(LibraryOperationKind.SCAN);
      assertThat(firstScan.statusBeforeOperation()).isEqualTo(LibraryStatus.HEALTHY);
      assertThat(libraryOperationScheduling.scheduledExecutions()).containsExactly(firstScan.id());
      assertThat(capturingEventPublisher.getEventsOfType(LibraryStatusChangedEvent.class))
          .extracting(LibraryStatusChangedEvent::libraryId)
          .containsExactly(savedLibrary.getId());
    }

    @Test
    @DisplayName(
        "Should throw LibraryOperationNotStartedException and roll back the add when the first"
            + " scan cannot be scheduled")
    void shouldThrowLibraryOperationNotStartedAndRollBackTheAddWhenTheFirstScanCannotBeScheduled()
        throws IOException {
      var newLibraryPath = fileSystem.getPath("/unscheduled-library");
      Files.createDirectories(newLibraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Unscheduled Library")
              .filepathUri(newLibraryPath.toString())
              .build();
      var schedulingFailure =
          new LibraryOperationNotScheduledException(
              UUID.randomUUID(), new IllegalStateException("task table unavailable"));
      libraryOperationScheduling.failNextWith(schedulingFailure);

      var thrown = catchThrowable(() -> libraryManagementService.addLibrary(identity, library));

      assertThat(thrown)
          .isInstanceOf(LibraryOperationNotStartedException.class)
          .hasCause(schedulingFailure);
      assertThat(transactionManager.commits()).isZero();
      assertThat(transactionManager.rollbacks()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should store no first scan when the add is refused for a registered path")
    void shouldStoreNoFirstScanWhenTheAddIsRefusedForARegisteredPath() throws IOException {
      var libraryPath = fileSystem.getPath("/registered-library");
      Files.createDirectories(libraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Registered Library")
              .filepathUri(libraryPath.toString())
              .build();
      var registered = accepted(libraryManagementService.addLibrary(identity, library));

      var duplicate =
          libraryManagementService.addLibrary(identity, library.toBuilder().name("Twin").build());

      assertThat(duplicate)
          .isEqualTo(
              Outcome.rejected(
                  new AddLibraryRejection.PathAlreadyRegistered(registered, libraryPath)));
      assertThat(libraryOperationScheduling.scheduledExecutions()).hasSize(1);
    }

    @Test
    @DisplayName(
        "Should publish LibraryAddedEvent with the persisted ID and encoded path when library is added")
    void shouldPublishLibraryAddedEventWhenLibraryIsAdded() throws IOException {
      var newLibraryPath = fileSystem.getPath("/event-library");
      Files.createDirectories(newLibraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Event Library")
              .filepathUri(newLibraryPath.toString())
              .build();
      assertThat(library.getId()).as("Input library should not have an ID").isNull();

      var savedLibrary = accepted(libraryManagementService.addLibrary(identity, library));

      var events = capturingEventPublisher.getEventsOfType(LibraryAddedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().libraryId()).isEqualTo(savedLibrary.getId()).isNotNull();
      assertThat(events.getFirst().filepathUri()).isEqualTo(FilepathCodec.encode(newLibraryPath));
    }

    @Test
    @DisplayName("Should not mutate input library when adding")
    void shouldNotMutateInputLibraryWhenAdding() throws IOException {
      var newLibraryPath = fileSystem.getPath("/no-mutate");
      Files.createDirectories(newLibraryPath);
      var library =
          LibraryFixtureCreator.unsavedLibraryBuilder()
              .name("Test Library")
              .filepathUri(newLibraryPath.toString())
              .build();

      libraryManagementService.addLibrary(identity, library);

      assertThat(library.getStatus()).as("Input library should not be mutated").isNull();
    }

    private Library accepted(Outcome<Library, AddLibraryRejection> outcome) {
      assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
      return ((Outcome.Accepted<Library, AddLibraryRejection>) outcome).result();
    }

    private Library registeredLibrary(String name, String folder) throws IOException {
      var path = Files.createDirectories(fileSystem.getPath(folder));
      return accepted(
          libraryManagementService.addLibrary(
              identity,
              LibraryFixtureCreator.unsavedLibraryBuilder()
                  .name(name)
                  .filepathUri(path.toString())
                  .build()));
    }

    private static Library libraryNamed(String name, String filepath) {
      return LibraryFixtureCreator.unsavedLibraryBuilder().name(name).filepathUri(filepath).build();
    }

    private static Library unsavedLibraryAt(Path folder) {
      return LibraryFixtureCreator.unsavedLibraryBuilder()
          .name("Added")
          .filepathUri(folder.toString())
          .build();
    }
  }

  @Nested
  @DisplayName("Remove Library Tests")
  class RemoveLibraryTests {

    @Test
    @DisplayName(
        "Should publish LibraryRemovedEvent with correct filepath and media file IDs when library is removed")
    void shouldPublishLibraryRemovedEventWhenLibraryIsRemoved() {
      var library = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
      var expectedFilepath = library.getFilepathUri();

      var mediaFile =
          fakeMediaFileRepository.save(
              MediaFile.builder()
                  .libraryId(savedLibraryId)
                  .filepathUri("/library/movie.mkv")
                  .filename("movie.mkv")
                  .status(MediaFileStatus.MATCHED)
                  .build());

      var outcome = libraryManagementService.removeLibrary(identity, savedLibraryId);

      assertThat(outcome).isEqualTo(Outcome.accepted(savedLibraryId));
      assertThat(fakeLibraryRepository.findById(savedLibraryId)).isEmpty();
      var events = capturingEventPublisher.getEventsOfType(LibraryRemovedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().libraryId()).isEqualTo(savedLibraryId);
      assertThat(events.getFirst().filepathUri()).isEqualTo(expectedFilepath);
      assertThat(events.getFirst().mediaFileIds()).containsExactly(mediaFile.getId());
    }

    @Test
    @DisplayName(
        "Should publish LibraryRemovedEvent with empty media file IDs when library has no media files")
    void shouldPublishLibraryRemovedEventWithEmptyMediaFileIdsWhenNoMediaFiles() {
      libraryManagementService.removeLibrary(identity, savedLibraryId);

      var events = capturingEventPublisher.getEventsOfType(LibraryRemovedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().mediaFileIds()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should refuse removal and publish no LibraryRemovedEvent when library does not exist")
    void shouldRefuseRemovalAndPublishNoLibraryRemovedEventWhenLibraryDoesNotExist() {
      var nonExistentId = UUID.randomUUID();

      var outcome = libraryManagementService.removeLibrary(identity, nonExistentId);

      assertThat(outcome)
          .isEqualTo(Outcome.rejected(new LibraryOperationRejection.LibraryNotFound()));
      var events = capturingEventPublisher.getEventsOfType(LibraryRemovedEvent.class);
      assertThat(events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(LibraryOperationKind.class)
    @DisplayName("Should refuse removal and keep the library while a library operation runs")
    void shouldRefuseRemovalAndKeepTheLibraryWhileALibraryOperationRuns(
        LibraryOperationKind runningKind) {
      libraryOperations.storeLibraryOperation(
          switch (runningKind) {
            case SCAN -> NewLibraryOperation.scan(savedLibraryId);
            case REFRESH -> NewLibraryOperation.refresh(savedLibraryId, ImageRefreshMode.PRESERVE);
          });

      var outcome = libraryManagementService.removeLibrary(identity, savedLibraryId);

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(
                  new LibraryOperationRejection.LibraryBusy(
                      runningKind.busyStatus(), runningKind)));
      assertThat(fakeLibraryRepository.findById(savedLibraryId)).isPresent();
      assertThat(capturingEventPublisher.getEventsOfType(LibraryRemovedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName(
        "Should let the failure escape and keep the library when the removal breaks a foreign key")
    void shouldLetTheFailureEscapeAndKeepTheLibraryWhenTheRemovalBreaksAForeignKey() {
      var message = "update or delete on table \"library\" violates foreign key constraint";
      var violation =
          new DataIntegrityViolationException(
              message,
              new ConstraintViolationException(
                  message, new SQLException(message, "23503"), "fk_library"));
      beforeDeletingLibrary =
          _ -> {
            throw violation;
          };

      var thrown =
          catchThrowable(() -> libraryManagementService.removeLibrary(identity, savedLibraryId));

      assertThat(thrown).isSameAs(violation);
      assertThat(fakeLibraryRepository.findById(savedLibraryId)).isPresent();
      assertThat(capturingEventPublisher.getEventsOfType(LibraryRemovedEvent.class)).isEmpty();
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

  private MovieFileProcessor movieFileProcessorWith(MetadataProvider<Movie> metadataProvider) {
    return new MovieFileProcessor(
        new DefaultVideoFileMetadataParser(),
        new ExternalIdVideoFileMetadataParser(),
        new MovieMetadataProviderResolver(List.of(metadataProvider)),
        movieService,
        fakeMediaFileRepository,
        new MutexFactoryProvider());
  }

  private SeriesFileProcessor seriesFileProcessorWith(SeriesMetadataProvider metadataProvider) {
    var metadataProviderResolver = new SeriesMetadataProviderResolver(List.of(metadataProvider));
    return new SeriesFileProcessor(
        new EpisodePathMetadataParser(new EpisodeRegexFixtures()),
        new SeasonPathMetadataParser(),
        new SeriesFolderNameParser(),
        metadataProviderResolver,
        new DateBasedEpisodeResolver(metadataProviderResolver),
        seriesService,
        fakeMediaFileRepository,
        new FakeSeasonRepository(),
        new FakeEpisodeRepository(),
        new MutexFactoryProvider());
  }

  private LibraryManagementService.LibraryManagementServiceBuilder serviceBuilder() {
    return LibraryManagementService.builder()
        .libraryFileWalk(fileWalkBuilder().build())
        .libraryRepository(fakeLibraryRepository)
        .libraryMetadataRepository(new FakeLibraryMetadataRepository())
        .mediaFileRepository(fakeMediaFileRepository)
        .movieService(movieService)
        .seriesService(seriesService)
        .eventPublisher(capturingEventPublisher)
        .fileSystem(fileSystem)
        .libraryMutationTransaction(libraryMutationTransaction)
        .mutationTransactions(mutationTransactions)
        .libraryOperations(libraryOperations)
        .libraryOperationService(libraryOperationService);
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

  private LibraryManagementService serviceWith(FileSystem alternateFileSystem) {
    return serviceBuilder()
        .fileSystem(alternateFileSystem)
        .libraryFileWalk(fileWalkBuilder().fileSystem(alternateFileSystem).build())
        .build();
  }

  private LibraryManagementService libraryManagementServiceWith(
      MovieFileProcessor movieProcessor, SeriesFileProcessor seriesProcessor) {
    return serviceBuilder()
        .libraryFileWalk(
            fileWalkBuilder()
                .movieFileProcessor(movieProcessor)
                .seriesFileProcessor(seriesProcessor)
                .build())
        .build();
  }

  private Path pathWithDisplayName(String filepathUri, String displayName) throws IOException {
    var path = mock(Path.class);
    var displayedFilename = mock(Path.class);
    var pathFileSystem = mock(FileSystem.class);
    var pathProvider = mock(FileSystemProvider.class);
    var fileAttributes = mock(BasicFileAttributes.class);
    when(path.getFileName()).thenReturn(displayedFilename);
    when(displayedFilename.toString()).thenReturn(displayName);
    when(path.toAbsolutePath()).thenReturn(path);
    when(path.toUri()).thenReturn(URI.create(filepathUri));
    when(path.getFileSystem()).thenReturn(pathFileSystem);
    when(pathFileSystem.provider()).thenReturn(pathProvider);
    when(pathProvider.readAttributes(path, BasicFileAttributes.class)).thenReturn(fileAttributes);
    when(fileAttributes.size()).thenReturn(1L);
    return path;
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
