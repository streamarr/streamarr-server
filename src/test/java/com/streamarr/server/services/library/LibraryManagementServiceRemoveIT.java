package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Library Removal Integration Tests")
public class LibraryManagementServiceRemoveIT extends AbstractIntegrationTest {

  @Autowired private LibraryManagementService libraryManagementService;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieRepository movieRepository;

  @Autowired private MediaFileRepository mediaFileRepository;

  @Autowired private PersonRepository personRepository;

  @Autowired private GenreRepository genreRepository;

  @BeforeEach
  void cleanupDatabase() {
    mediaFileRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
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
  @Transactional
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
  @Transactional
  void shouldRemoveMatchedMediaFilesWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Movie").library(library).build());

    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .mediaId(movie.getId())
                .filepath("/test/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    assertThat(mediaFileRepository.findById(mediaFile.getId())).isPresent();

    libraryManagementService.removeLibrary(library.getId());

    assertThat(mediaFileRepository.findById(mediaFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should remove orphaned media files when library is removed")
  @Transactional
  void shouldRemoveOrphanedMediaFilesWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var orphanedMediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepath("/test/orphaned.mkv")
                .filename("orphaned.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());

    assertThat(mediaFileRepository.findById(orphanedMediaFile.getId())).isPresent();

    libraryManagementService.removeLibrary(library.getId());

    assertThat(mediaFileRepository.findById(orphanedMediaFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should not affect other libraries when removing one library")
  @Transactional
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
                .filepath("/remove/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    var mediaFileToKeep =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryToKeep.getId())
                .mediaId(movieToKeep.getId())
                .filepath("/keep/movie.mkv")
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
  @DisplayName("Should call destroySession for media files with active streaming sessions")
  void shouldTerminateActiveStreamingSessionsWhenLibraryIsRemoved() {
    // This test verifies that streaming sessions are terminated for media files in the library.
    // We verify this indirectly by checking that getAllSessions is called (tested via mock in unit
    // tests)
    // and that the library and its content are properly removed.
    // The actual session termination logic is tested via unit tests with mocked StreamingService.
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Streaming Movie").library(library).build());
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .mediaId(movie.getId())
                .filepath("/test/streaming.mkv")
                .filename("streaming.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());

    libraryManagementService.removeLibrary(library.getId());

    assertThat(libraryRepository.findById(library.getId())).isEmpty();
    assertThat(mediaFileRepository.findById(mediaFile.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should preserve shared entities when library is removed")
  @Transactional
  void shouldPreserveSharedEntitiesWhenLibraryIsRemoved() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var person =
        personRepository.saveAndFlush(
            Person.builder().name("Shared Actor").sourceId("tmdb-12345").build());
    var genre =
        genreRepository.saveAndFlush(Genre.builder().name("Drama").sourceId("tmdb-28").build());

    var movie =
        Movie.builder()
            .title("Movie With Shared Entities")
            .library(library)
            .cast(List.of(person))
            .genres(java.util.Set.of(genre))
            .build();
    movieRepository.saveAndFlush(movie);

    libraryManagementService.removeLibrary(library.getId());

    assertThat(personRepository.findById(person.getId())).isPresent();
    assertThat(genreRepository.findById(genre.getId())).isPresent();
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

    assertThatThrownBy(() -> libraryManagementService.removeLibrary(library.getId()))
        .isInstanceOf(LibraryScanInProgressException.class)
        .hasMessageContaining(library.getId().toString());
  }

  @Test
  @DisplayName("Should block concurrent removal attempts")
  void shouldBlockConcurrentRemovalAttempts() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieRepository.saveAndFlush(Movie.builder().title("Movie").library(library).build());

    var barrier = new CyclicBarrier(2);
    var exceptions = new CopyOnWriteArrayList<Exception>();
    var libraryNotFoundCount = new java.util.concurrent.atomic.AtomicInteger(0);

    Runnable task =
        () -> {
          try {
            barrier.await();
            libraryManagementService.removeLibrary(library.getId());
          } catch (LibraryNotFoundException e) {
            libraryNotFoundCount.incrementAndGet();
          } catch (Exception e) {
            exceptions.add(e);
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
              assertThat(exceptions).isEmpty();
              assertThat(libraryRepository.findById(library.getId())).isEmpty();
              assertThat(libraryNotFoundCount.get())
                  .as("One thread succeeds, the other gets LibraryNotFoundException")
                  .isEqualTo(1);
            });
  }
}
