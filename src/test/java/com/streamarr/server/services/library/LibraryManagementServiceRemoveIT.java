package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.convention.TestBean;

@Tag("IntegrationTest")
@DisplayName("Library Removal Integration Tests")
class LibraryManagementServiceRemoveIT extends AbstractIntegrationTest {

  @Autowired private LibraryManagementService libraryManagementService;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieRepository movieRepository;

  @Autowired private SeriesRepository seriesRepository;

  @Autowired private SeasonRepository seasonRepository;

  @Autowired private EpisodeRepository episodeRepository;

  @Autowired private MediaFileRepository mediaFileRepository;

  @Autowired private PersonRepository personRepository;

  @Autowired private GenreRepository genreRepository;

  @Autowired private StreamingService streamingService;

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;

  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();
  private static final FakeFfprobeService FAKE_FFPROBE = new FakeFfprobeService();
  private static final FakeSegmentStore FAKE_SEGMENT_STORE = new FakeSegmentStore();

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  static FfprobeService ffprobeService() {
    return FAKE_FFPROBE;
  }

  static SegmentStore segmentStore() {
    return FAKE_SEGMENT_STORE;
  }

  @BeforeEach
  void cleanupDatabase() {
    mediaFileRepository.deleteAll();
    episodeRepository.deleteAll();
    seasonRepository.deleteAll();
    seriesRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
    FAKE_EXECUTOR.reset();
  }

  @Test
  @DisplayName("Should remove library when library exists with no content")
  void shouldRemoveLibraryWhenLibraryExistsWithNoContent() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());

    libraryManagementService.removeLibrary(library.getId());

    assertThat(libraryRepository.findById(library.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should remove all movies when library is removed")
  void shouldRemoveAllMoviesWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(Movie.builder().title("Movie One").library(library).build());
    movieRepository.saveAndFlush(Movie.builder().title("Movie Two").library(library).build());

    assertThat(movieRepository.findAll()).hasSize(2);

    libraryManagementService.removeLibrary(library.getId());

    assertThat(movieRepository.findAll()).isEmpty();
    assertThat(libraryRepository.findById(library.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should remove matched media files when library is removed")
  void shouldRemoveMatchedMediaFilesWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Movie").library(library).build());

    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .mediaId(movie.getId())
                .filepathUri("/test/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    assertThat(mediaFileRepository.findById(mediaFile.getId())).isPresent();

    libraryManagementService.removeLibrary(library.getId());

    assertThat(mediaFileRepository.findById(mediaFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should remove orphaned media files when library is removed")
  void shouldRemoveOrphanedMediaFilesWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var orphanedMediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("/test/orphaned.mkv")
                .filename("orphaned.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    assertThat(mediaFileRepository.findById(orphanedMediaFile.getId())).isPresent();

    libraryManagementService.removeLibrary(library.getId());

    assertThat(mediaFileRepository.findById(orphanedMediaFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should not affect other libraries when removing one library")
  void shouldNotAffectOtherLibrariesWhenRemovingOneLibrary() {
    var libraryToRemove = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var libraryToKeep = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var movieToRemove =
        movieRepository.saveAndFlush(
            Movie.builder().title("To Remove").library(libraryToRemove).build());
    var movieToKeep =
        movieRepository.saveAndFlush(
            Movie.builder().title("To Keep").library(libraryToKeep).build());

    var mediaFileToRemove =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryToRemove.getId())
                .mediaId(movieToRemove.getId())
                .filepathUri("/remove/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    var mediaFileToKeep =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryToKeep.getId())
                .mediaId(movieToKeep.getId())
                .filepathUri("/keep/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    libraryManagementService.removeLibrary(libraryToRemove.getId());

    assertThat(libraryRepository.findById(libraryToRemove.getId())).isEmpty();
    assertThat(libraryRepository.findById(libraryToKeep.getId())).isPresent();
    assertThat(movieRepository.findById(movieToRemove.getId())).isEmpty();
    assertThat(movieRepository.findById(movieToKeep.getId())).isPresent();
    assertThat(mediaFileRepository.findById(mediaFileToRemove.getId())).isEmpty();
    assertThat(mediaFileRepository.findById(mediaFileToKeep.getId())).isPresent();
  }

  @Test
  @DisplayName("Should terminate active streaming sessions when library is removed")
  void shouldTerminateActiveStreamingSessionsWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Streaming Movie").library(library).build());
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .mediaId(movie.getId())
                .filepathUri("/test/streaming.mkv")
                .filename("streaming.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    var session = streamingService.createSession(mediaFile.getId(), options);

    assertThat(streamingService.getActiveSessionCount()).isEqualTo(1);

    libraryManagementService.removeLibrary(library.getId());

    assertThat(streamingService.getActiveSessionCount())
        .as("Streaming session should be terminated when library is removed")
        .isZero();
    assertThat(FAKE_EXECUTOR.getStopped())
        .as("Transcode process should be stopped")
        .contains(session.getSessionId());
  }

  @Test
  @DisplayName("Should preserve shared entities when library is removed")
  void shouldPreserveSharedEntitiesWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var person =
        personRepository.saveAndFlush(
            Person.builder().name("Shared Actor").sourceId("tmdb-12345").build());
    var genre =
        genreRepository.saveAndFlush(Genre.builder().name("Drama").sourceId("tmdb-28").build());

    var personId = person.getId();
    var genreId = genre.getId();

    var movie = Movie.builder().title("Movie With Shared Entities").library(library).build();
    var savedMovie = movieRepository.saveAndFlush(movie);
    savedMovie.getCast().add(person);
    savedMovie.getGenres().add(genre);
    movieRepository.saveAndFlush(savedMovie);

    libraryManagementService.removeLibrary(library.getId());

    assertThat(personRepository.findById(personId)).isPresent();
    assertThat(genreRepository.findById(genreId)).isPresent();
  }

  @Test
  @DisplayName("Should throw LibraryNotFoundException when library does not exist")
  void shouldThrowLibraryNotFoundExceptionWhenLibraryDoesNotExist() {
    var nonExistentId = UUID.randomUUID();

    assertThatThrownBy(() -> libraryManagementService.removeLibrary(nonExistentId))
        .isInstanceOf(LibraryNotFoundException.class)
        .hasMessageContaining(nonExistentId.toString());
  }

  @Test
  @DisplayName("Should throw LibraryScanInProgressException when library is scanning")
  void shouldThrowLibraryScanInProgressExceptionWhenLibraryIsScanning() {
    var libraryToSave = LibraryFixtureCreator.buildFakeLibrary();
    libraryToSave.setStatus(LibraryStatus.SCANNING);
    var library = libraryRepository.saveAndFlush(libraryToSave);

    var libraryId = library.getId();

    assertThatThrownBy(() -> libraryManagementService.removeLibrary(libraryId))
        .isInstanceOf(LibraryScanInProgressException.class)
        .hasMessageContaining(libraryId.toString());
  }

  @Test
  @DisplayName("Should allow only one removal when concurrent attempts occur")
  void shouldAllowOnlyOneRemovalWhenConcurrentAttemptsOccur() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieRepository.saveAndFlush(Movie.builder().title("Movie").library(library).build());

    var barrier = new CyclicBarrier(2);
    var unexpectedExceptions = new CopyOnWriteArrayList<Exception>();
    var concurrentDeleteFailures = new AtomicInteger(0);

    Runnable task =
        () -> {
          try {
            barrier.await();
            libraryManagementService.removeLibrary(library.getId());
          } catch (LibraryNotFoundException | ObjectOptimisticLockingFailureException _) {
            concurrentDeleteFailures.incrementAndGet();
          } catch (Exception e) {
            unexpectedExceptions.add(e);
          }
        };

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(task);
      executor.submit(task);
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(unexpectedExceptions).isEmpty();
              assertThat(libraryRepository.findById(library.getId())).isEmpty();
              assertThat(concurrentDeleteFailures.get())
                  .as("One thread succeeds, the other fails with concurrent delete exception")
                  .isEqualTo(1);
            });
  }

  @Test
  @DisplayName("Should remove all series, seasons, and episodes when series library is removed")
  void shouldRemoveAllSeriesSeasonsAndEpisodesWhenSeriesLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title("Breaking Bad").library(library).build());
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Season 1")
                .seasonNumber(1)
                .series(series)
                .library(library)
                .build());
    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .title("Pilot")
                .episodeNumber(1)
                .season(season)
                .library(library)
                .build());

    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .mediaId(episode.getId())
                .filepathUri("/test/breaking.bad.s01e01.mkv")
                .filename("breaking.bad.s01e01.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    assertThat(seriesRepository.findAll()).hasSize(1);
    assertThat(seasonRepository.findAll()).hasSize(1);
    assertThat(episodeRepository.findAll()).hasSize(1);
    assertThat(mediaFileRepository.findAll()).hasSize(1);

    libraryManagementService.removeLibrary(library.getId());

    assertThat(libraryRepository.findById(library.getId())).isEmpty();
    assertThat(seriesRepository.findAll()).isEmpty();
    assertThat(seasonRepository.findAll()).isEmpty();
    assertThat(episodeRepository.findAll()).isEmpty();
    assertThat(mediaFileRepository.findById(mediaFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should NOT delete actual files on disk when library is removed")
  void shouldNotDeleteActualFilesOnDiskWhenLibraryIsRemoved(@TempDir Path tempDir)
      throws IOException {
    var movieFile = tempDir.resolve("Movie (2023).mkv");
    var subtitleFile = tempDir.resolve("Movie (2023).srt");
    Files.createFile(movieFile);
    Files.createFile(subtitleFile);
    Files.writeString(movieFile, "fake video content");
    Files.writeString(subtitleFile, "fake subtitle content");

    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildUnsavedLibrary("Test Library", tempDir.toString())
                .toBuilder()
                .status(LibraryStatus.HEALTHY)
                .build());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Movie").library(library).build());
    mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .filepathUri(movieFile.toString())
            .filename(movieFile.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .build());

    assertThat(Files.exists(movieFile)).isTrue();
    assertThat(Files.exists(subtitleFile)).isTrue();

    libraryManagementService.removeLibrary(library.getId());

    assertThat(libraryRepository.findById(library.getId())).isEmpty();
    assertThat(movieRepository.findById(movie.getId())).isEmpty();
    assertThat(Files.exists(movieFile))
        .as("Actual video file on disk must NOT be deleted")
        .isTrue();
    assertThat(Files.exists(subtitleFile))
        .as("Other files in library directory must NOT be deleted")
        .isTrue();
    assertThat(Files.readString(movieFile))
        .as("File content must remain intact")
        .isEqualTo("fake video content");
  }
}
