package com.streamarr.server.services.library;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderFactory;
import com.streamarr.server.services.metadata.movie.TMDBMovieProvider;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("Library Management Service Tests")
public class LibraryManagementServiceTest {

    private final Logger testLogger = LoggerFactory.getLogger(LibraryManagementServiceTest.class);
    private final MovieService movieService = mock(MovieService.class);
    private final MetadataProvider<Movie> tmdbMovieProvider = mock(TMDBMovieProvider.class);
    private final MovieMetadataProviderFactory fakeMovieMetadataProviderFactory = new MovieMetadataProviderFactory(List.of(tmdbMovieProvider), testLogger);
    private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
    private final MediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
    private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());

    private final LibraryManagementService libraryManagementService = new LibraryManagementService(
        new VideoExtensionValidator(),
        new DefaultVideoFileMetadataParser(),
        fakeMovieMetadataProviderFactory,
        fakeLibraryRepository,
        fakeMediaFileRepository,
        movieService,
        testLogger,
        new MutexFactoryProvider(),
        fileSystem
    );

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
        var ex = assertThrows(RuntimeException.class, () -> libraryManagementService.scanLibrary(UUID.randomUUID()));
        assertThat(ex).hasMessage("Library cannot be found for scanning.");

    }

    @Test
    @DisplayName("Should not allow for scanning of a library that is currently being scanned.")
    void shouldFailWhenLibraryCurrentlyBeingScanned() {
        var library = fakeLibraryRepository.findById(savedLibraryId);
        library.orElseThrow().setStatus(LibraryStatus.SCANNING);
        fakeLibraryRepository.save(library.orElseThrow());

        var ex = assertThrows(RuntimeException.class, () -> libraryManagementService.scanLibrary(savedLibraryId));
        assertThat(ex).hasMessage("Library is currently being scanned.");
    }

    @Test
    @DisplayName("Should set library status to unhealthy when the library filepath cannot be accessed")
    void shouldSetLibraryStatusToUnhealthyWhenLibraryFilepathInaccessible() {
        libraryManagementService.scanLibrary(savedLibraryId);

        assertTrue(fakeLibraryRepository.findById(savedLibraryId).isPresent());
        assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("Should set library status to healthy when the library filepath can be accessed")
    void shouldSetLibraryStatusToHealthyWhenLibraryFilepathAccessible() throws IOException {
        createRootLibraryDirectory();

        libraryManagementService.scanLibrary(savedLibraryId);

        assertTrue(fakeLibraryRepository.findById(savedLibraryId).isPresent());
        assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus()).isEqualTo(LibraryStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should skip creating a media file when provided a library containing an unsupported file extension")
    void shouldSkipCreatingMediaFileWhenProvidedLibraryContainingUnsupportedMovie() throws IOException {
        // This tradeoff increases scan speed in worst case scenarios where a library contains a large number of unsupported files.
        var rootPath = createRootLibraryDirectory();
        var moviePath = createMovieFile(rootPath, "About Time", "About Time (2013).av1");

        libraryManagementService.scanLibrary(savedLibraryId);

        var mediaFile = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFile.isEmpty());
    }

    @Test
    @DisplayName("Should skip updating metadata when provided a media file that has already been matched.")
    void shouldSkipUpdatingMetadataWhenProvidedMediaFileThatHasBeenMatched() throws IOException {
        var movieFolder = "About Time";
        var movieFilename = "About Time (2013).mkv";

        var rootPath = createRootLibraryDirectory();
        var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

        var mediaFileBeforeRefresh = fakeMediaFileRepository.save(MediaFile.builder()
            .libraryId(savedLibraryId)
            .filepath(moviePath.toString())
            .filename(movieFilename)
            .status(MediaFileStatus.MATCHED)
            .build());

        libraryManagementService.scanLibrary(savedLibraryId);

        var mediaFileAfterRefresh = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFileAfterRefresh.isPresent());
        assertEquals(mediaFileBeforeRefresh, mediaFileAfterRefresh.get());
    }

    @Test
    @DisplayName("Should match media file when provided a library containing a file with a supported extension")
    void shouldMatchMediaFileWhenProvidedLibraryContainingSupportedMovie() throws IOException {
        var movieFolder = "About Time";
        var movieFilename = "About Time (2013).mkv";

        var rootPath = createRootLibraryDirectory();
        var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

        when(tmdbMovieProvider.getAgentStrategy()).thenReturn(ExternalAgentStrategy.TMDB);

        when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(Optional.of(RemoteSearchResult.builder()
            .title(movieFolder)
            .externalId("123")
            .externalSourceType(ExternalSourceType.TMDB)
            .build()));

        when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class)))
            .thenReturn(Optional.of(Movie.builder()
                .title(movieFolder)
                .build()));

        libraryManagementService.scanLibrary(savedLibraryId);

        var mediaFile = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFile.isPresent());

        // TODO: Failing because we don't have an impl of FakeMovieRepository that would update the MediaFile...
        assertEquals(MediaFileStatus.MATCHED, mediaFile.get().getStatus());
    }

    @Test
    @DisplayName("Should match media file when provided a library containing an existing unmatched movie")
    void shouldMatchMediaFileWhenProvidedLibraryContainingExistingUnmatchedMovie() throws IOException {
        var movieFolder = "About Time";
        var movieFilename = "About Time (2013).mkv";

        var rootPath = createRootLibraryDirectory();
        var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

        var mediaFileBeforeRefresh = fakeMediaFileRepository.save(MediaFile.builder()
            .libraryId(savedLibraryId)
            .filepath(moviePath.toString())
            .filename(movieFilename)
            .status(MediaFileStatus.UNMATCHED)
            .build());

        when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(Optional.of(RemoteSearchResult.builder()
            .title(movieFolder)
            .externalId("123")
            .build()));

        when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class))).thenReturn(Optional.of(Movie.builder().build()));

        libraryManagementService.scanLibrary(savedLibraryId);

        var mediaFileAfterRefresh = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFileAfterRefresh.isPresent());
        assertEquals(mediaFileBeforeRefresh, mediaFileAfterRefresh.get());
    }


    private Path createRootLibraryDirectory() throws IOException {
        var library = fakeLibraryRepository.findById(savedLibraryId);

        var path = fileSystem.getPath(library.orElseThrow().getFilepath());
        Files.createDirectory(path);

        return path;
    }

    private Path createMovieFile(Path libraryRoot, String folder, String filename) throws IOException {
        var movieFolder = libraryRoot.resolve(folder);
        Files.createDirectory(movieFolder);
        var movieFile = movieFolder.resolve(filename);
        Files.createFile(movieFile);

        return movieFile;
    }
}
