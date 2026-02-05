package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.streamarr.server.exceptions.InvalidLibraryPathException;
import com.streamarr.server.exceptions.LibraryAccessDeniedException;
import com.streamarr.server.exceptions.LibraryAlreadyExistsException;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.SecurityExceptionFileSystem;
import com.streamarr.server.fakes.ThrowingFileSystemWrapper;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("Library Management Service Tests")
public class LibraryManagementServiceTest {

  private final PersonService personService = mock(PersonService.class);
  private final GenreService genreService = mock(GenreService.class);
  private final MetadataProvider<Movie> tmdbMovieProvider = mock(TMDBMovieProvider.class);
  private final MovieMetadataProviderResolver fakeMovieMetadataProviderResolver =
      new MovieMetadataProviderResolver(List.of(tmdbMovieProvider));
  private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
  private final MediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
  private final MovieRepository fakeMovieRepository = new FakeMovieRepository();
  private final MovieService movieService = new MovieService(fakeMovieRepository, null, null);
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());

  private final OrphanedMediaFileCleanupService orphanedMediaFileCleanupService =
      new OrphanedMediaFileCleanupService(fakeMediaFileRepository, movieService, fileSystem);

  private final LibraryManagementService libraryManagementService =
      new LibraryManagementService(
          new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
          new VideoExtensionValidator(),
          new DefaultVideoFileMetadataParser(),
          fakeMovieMetadataProviderResolver,
          fakeLibraryRepository,
          fakeMediaFileRepository,
          movieService,
          personService,
          genreService,
          orphanedMediaFileCleanupService,
          new MutexFactoryProvider(),
          fileSystem);

  private UUID savedLibraryId;

  @BeforeEach
  public void setup() {
    var fakeLibrary = LibraryFixtureCreator.buildFakeLibrary();
    var savedLibrary = fakeLibraryRepository.save(fakeLibrary);

    savedLibraryId = savedLibrary.getId();
  }

  @AfterEach
  public void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should not allow for scanning of a library that doesn't exist.")
  void shouldFailWhenNoLibraryFound() {
    assertThrows(
        LibraryNotFoundException.class,
        () -> libraryManagementService.scanLibrary(UUID.randomUUID()));
  }

  @Test
  @DisplayName("Should not allow for scanning of a library that is currently being scanned.")
  void shouldFailWhenLibraryCurrentlyBeingScanned() {
    var library = fakeLibraryRepository.findById(savedLibraryId);
    library.orElseThrow().setStatus(LibraryStatus.SCANNING);
    fakeLibraryRepository.save(library.orElseThrow());

    assertThrows(
        LibraryScanInProgressException.class,
        () -> libraryManagementService.scanLibrary(savedLibraryId));
  }

  @Test
  @DisplayName(
      "Should set library status to unhealthy when the library filepath cannot be accessed")
  void shouldSetLibraryStatusToUnhealthyWhenLibraryFilepathInaccessible() {
    libraryManagementService.scanLibrary(savedLibraryId);

    assertTrue(fakeLibraryRepository.findById(savedLibraryId).isPresent());
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

    var serviceWithThrowingFs =
        new LibraryManagementService(
            new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
            new VideoExtensionValidator(),
            new DefaultVideoFileMetadataParser(),
            fakeMovieMetadataProviderResolver,
            fakeLibraryRepository,
            fakeMediaFileRepository,
            movieService,
            personService,
            genreService,
            orphanedMediaFileCleanupService,
            new MutexFactoryProvider(),
            throwingFileSystem);

    serviceWithThrowingFs.scanLibrary(savedLibraryId);

    assertTrue(fakeLibraryRepository.findById(savedLibraryId).isPresent());
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

    var serviceWithThrowingFs =
        new LibraryManagementService(
            new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
            new VideoExtensionValidator(),
            new DefaultVideoFileMetadataParser(),
            fakeMovieMetadataProviderResolver,
            fakeLibraryRepository,
            fakeMediaFileRepository,
            movieService,
            personService,
            genreService,
            orphanedMediaFileCleanupService,
            new MutexFactoryProvider(),
            throwingFileSystem);

    serviceWithThrowingFs.scanLibrary(savedLibraryId);

    assertTrue(fakeLibraryRepository.findById(savedLibraryId).isPresent());
    assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
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
                .filepath("/library/" + UUID.randomUUID())
                .externalAgentStrategy(ExternalAgentStrategy.TMDB)
                .type(MediaType.OTHER)
                .build());

    var libraryPath = fileSystem.getPath(otherTypeLibrary.getFilepath());
    Files.createDirectories(libraryPath);
    var movieFolder = libraryPath.resolve("Test Movie");
    Files.createDirectory(movieFolder);
    var movieFile = movieFolder.resolve("Test Movie (2024).mkv");
    Files.createFile(movieFile);

    assertThrows(
        IllegalStateException.class,
        () -> libraryManagementService.processDiscoveredFile(otherTypeLibrary.getId(), movieFile));
  }

  @Test
  @DisplayName("Should set library status to healthy when the library filepath can be accessed")
  void shouldSetLibraryStatusToHealthyWhenLibraryFilepathAccessible() throws IOException {
    createRootLibraryDirectory();

    libraryManagementService.scanLibrary(savedLibraryId);

    assertTrue(fakeLibraryRepository.findById(savedLibraryId).isPresent());
    assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName(
      "Should skip creating a media file when provided a library containing an unsupported file extension")
  void shouldSkipCreatingMediaFileWhenProvidedLibraryContainingUnsupportedMovie()
      throws IOException {
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "About Time", "About Time (2013).av1");

    libraryManagementService.scanLibrary(savedLibraryId);

    var mediaFile =
        fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

    assertTrue(mediaFile.isEmpty());
  }

  @Test
  @DisplayName(
      "Should skip updating metadata when provided a media file that has already been matched.")
  void shouldSkipUpdatingMetadataWhenProvidedMediaFileThatHasBeenMatched() throws IOException {
    var movieFolder = "About Time";
    var movieFilename = "About Time (2013).mkv";

    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

    var mediaFileBeforeRefresh =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(savedLibraryId)
                .filepath(moviePath.toString())
                .filename(movieFilename)
                .status(MediaFileStatus.MATCHED)
                .build());

    libraryManagementService.scanLibrary(savedLibraryId);

    var mediaFileAfterRefresh =
        fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

    assertTrue(mediaFileAfterRefresh.isPresent());
    assertEquals(mediaFileBeforeRefresh, mediaFileAfterRefresh.get());
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
            Optional.of(
                RemoteSearchResult.builder()
                    .title(movieFolder)
                    .externalId("123")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(Optional.of(Movie.builder().title(movieFolder).build()));

    libraryManagementService.scanLibrary(savedLibraryId);

    var mediaFile =
        fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

    assertTrue(mediaFile.isPresent());

    assertEquals(MediaFileStatus.MATCHED, mediaFile.get().getStatus());
  }

  @Test
  @DisplayName(
      "Should match media file when provided a library containing an existing unmatched movie")
  void shouldMatchMediaFileWhenProvidedLibraryContainingExistingUnmatchedMovie()
      throws IOException {
    var movieFolder = "About Time";
    var movieFilename = "About Time (2013).mkv";

    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

    var mediaFileBeforeRefresh =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(savedLibraryId)
                .filepath(moviePath.toString())
                .filename(movieFilename)
                .status(MediaFileStatus.UNMATCHED)
                .build());

    when(tmdbMovieProvider.search(any(VideoFileParserResult.class)))
        .thenReturn(
            Optional.of(RemoteSearchResult.builder().title(movieFolder).externalId("123").build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(Optional.of(Movie.builder().build()));

    libraryManagementService.scanLibrary(savedLibraryId);

    var mediaFileAfterRefresh =
        fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

    assertTrue(mediaFileAfterRefresh.isPresent());
    assertEquals(mediaFileBeforeRefresh, mediaFileAfterRefresh.get());
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
            Optional.of(
                RemoteSearchResult.builder()
                    .title(movieFolder)
                    .externalId("456")
                    .externalSourceType(ExternalSourceType.TMDB)
                    .build()));

    when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
        .thenReturn(Optional.of(Movie.builder().title(movieFolder).build()));

    libraryManagementService.processDiscoveredFile(savedLibraryId, moviePath);

    var mediaFile =
        fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

    assertTrue(mediaFile.isPresent());
    assertEquals(MediaFileStatus.MATCHED, mediaFile.get().getStatus());
  }

  @Test
  @DisplayName("Should throw when library not found for discovered file")
  void shouldThrowWhenLibraryNotFoundForDiscoveredFile() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var moviePath = createMovieFile(rootPath, "Test", "Test (2024).mkv");

    assertThrows(
        LibraryNotFoundException.class,
        () -> libraryManagementService.processDiscoveredFile(UUID.randomUUID(), moviePath));
  }

  @Test
  @DisplayName("Should not attempt cleanup when library path is inaccessible")
  void shouldNotAttemptCleanupWhenLibraryPathInaccessible() {
    var orphanedMediaFile =
        fakeMediaFileRepository.save(
            MediaFile.builder()
                .libraryId(savedLibraryId)
                .filepath("/library/nonexistent/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    libraryManagementService.scanLibrary(savedLibraryId);

    assertThat(fakeMediaFileRepository.findById(orphanedMediaFile.getId())).isPresent();
  }

  @Test
  @DisplayName(
      "Should skip processing when provided a library containing a system file with a valid extension")
  void shouldSkipProcessingWhenGivenLibraryContainingSystemFile() throws IOException {
    var rootPath = createRootLibraryDirectory();
    var systemFilePath = createMovieFile(rootPath, "About Time", "._About Time (2013).mkv");

    libraryManagementService.scanLibrary(savedLibraryId);

    var mediaFile =
        fakeMediaFileRepository.findFirstByFilepath(systemFilePath.toAbsolutePath().toString());

    assertTrue(mediaFile.isEmpty());
  }

  @Test
  @DisplayName("Should create only one MediaFile when concurrent calls probe same filepath")
  void shouldCreateOnlyOneMediaFileWhenConcurrentCallsProbeSameFilepath() throws Exception {
    var rootPath = createRootLibraryDirectory();
    var path = createMovieFile(rootPath, "Concurrent Test", "Concurrent Test (2024).mkv");
    var barrier = new CyclicBarrier(2);
    var exceptions = new CopyOnWriteArrayList<Exception>();

    Runnable task =
        () -> {
          try {
            barrier.await();
            libraryManagementService.processDiscoveredFile(savedLibraryId, path);
          } catch (Exception e) {
            exceptions.add(e);
          }
        };

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(task);
      executor.submit(task);
    }

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(exceptions).isEmpty();
              var mediaFiles = fakeMediaFileRepository.findByLibraryId(savedLibraryId);
              assertThat(mediaFiles)
                  .as("Expected exactly one MediaFile for the same filepath")
                  .hasSize(1);
            });
  }

  // ==================== addLibrary Tests ====================

  @Test
  @DisplayName("Should throw InvalidLibraryPathException when filepath is null")
  void shouldThrowInvalidLibraryPathExceptionWhenFilepathIsNull() {
    var library = LibraryFixtureCreator.buildUnsavedLibrary("Test Library", null);

    assertThrows(
        InvalidLibraryPathException.class, () -> libraryManagementService.addLibrary(library));
  }

  @Test
  @DisplayName("Should throw InvalidLibraryPathException when filepath is blank")
  void shouldThrowInvalidLibraryPathExceptionWhenFilepathIsBlank() {
    var library = LibraryFixtureCreator.buildUnsavedLibrary("Test Library", "   ");

    assertThrows(
        InvalidLibraryPathException.class, () -> libraryManagementService.addLibrary(library));
  }

  @Test
  @DisplayName("Should throw LibraryAlreadyExistsException when filepath already exists")
  void shouldThrowLibraryAlreadyExistsExceptionWhenFilepathExists() throws IOException {
    var existingLibrary = fakeLibraryRepository.findById(savedLibraryId).orElseThrow();
    var existingFilepath = existingLibrary.getFilepath();

    var libraryPath = fileSystem.getPath(existingFilepath);
    Files.createDirectories(libraryPath);

    var duplicateLibrary =
        LibraryFixtureCreator.buildUnsavedLibrary("Duplicate Library", existingFilepath);

    assertThrows(
        LibraryAlreadyExistsException.class,
        () -> libraryManagementService.addLibrary(duplicateLibrary));
  }

  @Test
  @DisplayName("Should throw InvalidLibraryPathException when path does not exist on disk")
  void shouldThrowInvalidLibraryPathExceptionWhenPathDoesNotExist() {
    var library = LibraryFixtureCreator.buildUnsavedLibrary("Test Library", "/nonexistent/path");

    assertThrows(
        InvalidLibraryPathException.class, () -> libraryManagementService.addLibrary(library));
  }

  @Test
  @DisplayName("Should throw InvalidLibraryPathException when path is not a directory")
  void shouldThrowInvalidLibraryPathExceptionWhenPathIsNotDirectory() throws IOException {
    var filePath = fileSystem.getPath("/library/file.txt");
    Files.createDirectories(filePath.getParent());
    Files.createFile(filePath);

    var library = LibraryFixtureCreator.buildUnsavedLibrary("Test Library", filePath.toString());

    assertThrows(
        InvalidLibraryPathException.class, () -> libraryManagementService.addLibrary(library));
  }

  @Test
  @DisplayName("Should save library and return with generated ID when valid library provided")
  void shouldSaveLibraryAndReturnWithGeneratedId() throws IOException {
    var newLibraryPath = fileSystem.getPath("/new-library");
    Files.createDirectories(newLibraryPath);

    var library =
        LibraryFixtureCreator.buildUnsavedLibrary("New Library", newLibraryPath.toString());

    var savedLibrary = libraryManagementService.addLibrary(library);

    assertThat(savedLibrary.getId()).isNotNull();
    assertThat(fakeLibraryRepository.findById(savedLibrary.getId())).isPresent();
  }

  @Test
  @DisplayName("Should set status to HEALTHY when adding library")
  void shouldSetStatusToHealthyWhenAdding() throws IOException {
    var newLibraryPath = fileSystem.getPath("/healthy-library");
    Files.createDirectories(newLibraryPath);

    var library =
        LibraryFixtureCreator.buildUnsavedLibrary("Healthy Library", newLibraryPath.toString());

    var savedLibrary = libraryManagementService.addLibrary(library);

    assertThat(savedLibrary.getStatus()).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should trigger library scan asynchronously after adding")
  void shouldTriggerLibraryScanAsynchronouslyAfterAdding() throws IOException {
    var newLibraryPath = fileSystem.getPath("/scan-library");
    Files.createDirectories(newLibraryPath);

    var library =
        LibraryFixtureCreator.buildUnsavedLibrary("Scan Library", newLibraryPath.toString());

    var savedLibrary = libraryManagementService.addLibrary(library);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var refreshedLibrary =
                  fakeLibraryRepository.findById(savedLibrary.getId()).orElseThrow();
              assertThat(refreshedLibrary.getScanCompletedOn())
                  .as("Library scan should complete")
                  .isNotNull();
              assertThat(refreshedLibrary.getStatus())
                  .as("Library status should be HEALTHY after scan")
                  .isEqualTo(LibraryStatus.HEALTHY);
            });
  }

  @Test
  @DisplayName("Should throw LibraryAccessDeniedException when path access denied by security manager")
  void shouldThrowLibraryAccessDeniedExceptionWhenPathAccessDenied() {
    var securityExceptionFs = new SecurityExceptionFileSystem(fileSystem);

    var serviceWithSecurityFs =
        new LibraryManagementService(
            new IgnoredFileValidator(new LibraryScanProperties(null, null, null)),
            new VideoExtensionValidator(),
            new DefaultVideoFileMetadataParser(),
            fakeMovieMetadataProviderResolver,
            fakeLibraryRepository,
            fakeMediaFileRepository,
            movieService,
            personService,
            genreService,
            orphanedMediaFileCleanupService,
            new MutexFactoryProvider(),
            securityExceptionFs);

    var library = LibraryFixtureCreator.buildUnsavedLibrary("Test Library", "/secure-path");

    var exception =
        assertThrows(
            LibraryAccessDeniedException.class,
            () -> serviceWithSecurityFs.addLibrary(library));

    assertThat(exception.getMessage()).contains("/secure-path");
  }

  @Test
  @DisplayName("Should not mutate input library when adding")
  void shouldNotMutateInputLibraryWhenAdding() throws IOException {
    var newLibraryPath = fileSystem.getPath("/no-mutate");
    Files.createDirectories(newLibraryPath);

    var library =
        LibraryFixtureCreator.buildUnsavedLibrary("Test Library", newLibraryPath.toString());

    libraryManagementService.addLibrary(library);

    assertThat(library.getStatus())
        .as("Input library should not be mutated")
        .isNull();
  }

  private Path createRootLibraryDirectory() throws IOException {
    var library = fakeLibraryRepository.findById(savedLibraryId);

    var path = fileSystem.getPath(library.orElseThrow().getFilepath());
    Files.createDirectories(path);

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
