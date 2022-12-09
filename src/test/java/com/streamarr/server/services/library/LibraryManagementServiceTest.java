package com.streamarr.server.services.library;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
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
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
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
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final MovieService movieService = mock(MovieService.class);
    private final MetadataProvider<Movie> tmdbMovieProvider = mock(TMDBMovieProvider.class);
    private final LibraryRepository fakeLibraryRepository = new FakeLibraryRepository();
    private final MediaFileRepository fakeMediaFileRepository = new FakeMediaFileRepository();
    private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());

    private final LibraryManagementService libraryManagementService = new LibraryManagementService(
        new VideoExtensionValidator(),
        new DefaultVideoFileMetadataParser(),
        tmdbMovieProvider,
        fakeLibraryRepository,
        fakeMediaFileRepository,
        movieService,
        LoggerFactory.getLogger(LibraryManagementServiceTest.class),
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
    @DisplayName("Should throw an exception when no library configured")
    void shouldFailWhenNoLibraryFound() {
        assertThrows(RuntimeException.class, () -> libraryManagementService.refreshLibrary(UUID.randomUUID()), "Library cannot be found for refresh.");
    }

    @Test
    @DisplayName("Should set library status to unhealthy when the library filepath cannot be accessed")
    void shouldSetLibraryStatusToUnhealthyWhenLibraryFilepathInaccessible() {
        libraryManagementService.refreshLibrary(savedLibraryId);

        assertTrue(fakeLibraryRepository.findById(savedLibraryId).isPresent());
        assertThat(fakeLibraryRepository.findById(savedLibraryId).get().getStatus()).isEqualTo(LibraryStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("Should scan a movie file and create an unmatched meda file when provided a valid library ")
    void shouldScanMovieAndCreateMediaFileWhenProvidedLibrary() throws IOException {
        var movieFolder = "About Time";
        var movieFilename = "About Time (2013).mkv";

        var rootPath = createRootLibraryDirectory();
        var moviePath = createMovieFile(rootPath, movieFolder, movieFilename);

        when(tmdbMovieProvider.search(any(VideoFileParserResult.class))).thenReturn(Optional.of(RemoteSearchResult.builder()
            .title(movieFolder)
            .externalId("123")
            .build()));

        when(tmdbMovieProvider.getMetadata(any(RemoteSearchResult.class), any(Library.class))).thenReturn(Optional.empty());

        libraryManagementService.refreshLibrary(savedLibraryId);

        var mediaFile = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFile.isPresent());
        assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.UNMATCHED);
    }

    @Test
    @DisplayName("Should scan a movie file and skip updating media file when provided a library containing an existing movie")
    void shouldScanMovieAndSkipUpdatingMediaFileWhenProvidedLibraryContainingExistingMovie() throws IOException {
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

        libraryManagementService.refreshLibrary(savedLibraryId);

        var mediaFileAfterRefresh = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFileAfterRefresh.isPresent());
        assertEquals(mediaFileBeforeRefresh, mediaFileAfterRefresh.get());
    }

    @Test
    @DisplayName("Should scan a movie file and match media file when provided a library containing an existing unmatched movie")
    void shouldScanMovieAndMatchMediaFileWhenProvidedLibraryContainingExistingUnmatchedMovie() throws IOException {
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

        libraryManagementService.refreshLibrary(savedLibraryId);

        var mediaFileAfterRefresh = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFileAfterRefresh.isPresent());
        assertEquals(mediaFileBeforeRefresh, mediaFileAfterRefresh.get());
    }

    @Test
    @DisplayName("Should scan a movie file and skip creating a media file when provided a library containing an unsupported file extension")
    void shouldScanMovieAndSkipCreatingMediaFileWhenProvidedLibraryContainingUnsupportedMovie() throws IOException {
        var rootPath = createRootLibraryDirectory();
        var moviePath = createMovieFile(rootPath, "About Time", "About Time (2013).av1");

        libraryManagementService.refreshLibrary(savedLibraryId);

        var mediaFile = fakeMediaFileRepository.findFirstByFilepath(moviePath.toAbsolutePath().toString());

        assertTrue(mediaFile.isEmpty());
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
