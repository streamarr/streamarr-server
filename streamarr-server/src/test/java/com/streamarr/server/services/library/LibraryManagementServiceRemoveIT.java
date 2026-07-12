package com.streamarr.server.services.library;

import static com.streamarr.server.jooq.generated.tables.StreamSession.STREAM_SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
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
import com.streamarr.server.exceptions.LibraryRefreshInProgressException;
import com.streamarr.server.exceptions.LibraryScanInProgressException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaSourceCatalog;
import com.streamarr.server.fakes.FakePlaybackTranscodeJobService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.enums.StreamSessionStatus;
import com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MediaParentDeletionRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.SessionScopeService;
import com.streamarr.server.services.streaming.CreatePlaybackSessionCommand;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.PersistedStreamSessionReaper;
import com.streamarr.server.services.streaming.PlaybackSessionCreationService;
import com.streamarr.server.services.streaming.PlaybackTranscodeJobService;
import com.streamarr.server.services.streaming.RuntimeTranscodeCleanup;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.source.MediaSourceCatalog;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Isolated
@Tag("IntegrationTest")
@DisplayName("Library Removal Integration Tests")
class LibraryManagementServiceRemoveIT extends AbstractIntegrationTest {

  @Autowired private LibraryManagementService libraryManagementService;

  @Autowired private OrphanedMediaFileCleanupService orphanedMediaFileCleanupService;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieRepository movieRepository;

  @Autowired private SeriesRepository seriesRepository;

  @Autowired private SeasonRepository seasonRepository;

  @Autowired private EpisodeRepository episodeRepository;

  @Autowired private MediaFileRepository mediaFileRepository;

  @Autowired private MediaParentDeletionRepository mediaParentDeletionRepository;

  @Autowired private PersonRepository personRepository;

  @Autowired private GenreRepository genreRepository;

  @Autowired private StreamingService streamingService;

  @Autowired private PlaybackSessionCreationService playbackSessionCreationService;

  @Autowired private PersistedStreamSessionReaper cleanupWorker;

  @Autowired private MediaParentDeletionRetryWorker deletionRetryWorker;

  @Autowired private MediaParentDeletionTransactions deletionTransactions;

  @Autowired private MediaParentDeletionService mediaParentDeletionService;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private SessionScopeService sessionScopeService;

  @Autowired private JwtDecoder jwtDecoder;

  @Autowired private DSLContext dsl;

  @TestBean PlaybackTranscodeJobService playbackTranscodeJobService;
  @TestBean MediaSourceCatalog libraryMediaSourceCatalog;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;

  private static final FakePlaybackTranscodeJobService FAKE_TRANSCODE_JOBS =
      new FakePlaybackTranscodeJobService();
  private static final FakeMediaSourceCatalog FAKE_SOURCE_CATALOG = new FakeMediaSourceCatalog();
  private static final FakeFfprobeService FAKE_FFPROBE = new FakeFfprobeService();
  private static final FakeSegmentStore FAKE_SEGMENT_STORE = new FakeSegmentStore();

  static PlaybackTranscodeJobService playbackTranscodeJobService() {
    return FAKE_TRANSCODE_JOBS;
  }

  static MediaSourceCatalog libraryMediaSourceCatalog() {
    return FAKE_SOURCE_CATALOG;
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
    FAKE_TRANSCODE_JOBS.reset();
  }

  @Test
  @DisplayName("Should reject media parent mutations inside ambient transactions")
  void shouldRejectMediaParentMutationsInsideAmbientTransactions() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    assertRejectsAmbientTransaction(
        () -> mediaParentDeletionService.deleteLibrary(library.getId()));
    assertRejectsAmbientTransaction(
        () -> mediaParentDeletionService.resumeLibraryDeletion(library.getId()));
    assertRejectsAmbientTransaction(
        () -> mediaParentDeletionService.deleteMediaFiles(library.getId(), Set.of()));
    assertRejectsAmbientTransaction(
        () -> mediaParentDeletionService.resumeMediaFileDeletion(UUID.randomUUID()));

    assertThat(libraryRepository.findById(library.getId())).isPresent();
    assertThat(intentExists("library_deletion_intent", "library_id", library.getId())).isFalse();
  }

  private void assertRejectsAmbientTransaction(Runnable mutation) {
    var transaction = new TransactionTemplate(transactionManager);
    assertThatThrownBy(() -> transaction.executeWithoutResult(_ -> mutation.run()))
        .isInstanceOf(IllegalTransactionStateException.class);
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
    var fixture = createActiveStream("Streaming Movie", "/test/streaming.mkv");
    try {
      assertThat(streamingService.getActiveSessionCount()).isEqualTo(1);

      libraryManagementService.removeLibrary(fixture.libraryId());

      assertThat(streamingService.getActiveSessionCount())
          .as("Streaming session should be terminated when library is removed")
          .isZero();
      assertThat(FAKE_TRANSCODE_JOBS.terminalCleanupAttempts())
          .as("Exact transcode cleanup should be attempted once")
          .containsExactly(fixture.streamSessionId());
    } finally {
      cleanupFixture(fixture);
    }
  }

  @Test
  @DisplayName("Should retain library deletion intent when stream cleanup fails")
  void shouldRetainLibraryDeletionIntentWhenStreamCleanupFails() {
    var fixture = createActiveStream("Retained Streaming Movie", "/test/retained-streaming.mkv");

    try {
      FAKE_TRANSCODE_JOBS.returnTerminalCleanup(
          fixture.streamSessionId(), RuntimeTranscodeCleanup.PENDING);

      libraryManagementService.removeLibrary(fixture.libraryId());

      assertThat(libraryRepository.findById(fixture.libraryId())).isPresent();
      assertThat(mediaFileRepository.findById(fixture.mediaFileId())).isPresent();
      assertSourceDeleted(fixture.streamSessionId());
      assertThat(intentExists("library_deletion_intent", "library_id", fixture.libraryId()))
          .isTrue();
      assertThat(intentExists("media_file_deletion_intent", "media_file_id", fixture.mediaFileId()))
          .isTrue();
      assertThat(FAKE_TRANSCODE_JOBS.terminalCleanupAttempts())
          .containsExactly(fixture.streamSessionId());
    } finally {
      cleanupFixture(fixture);
    }
  }

  @Test
  @DisplayName("Should complete persisted library deletion when cleanup retry succeeds")
  void shouldCompletePersistedLibraryDeletionWhenCleanupRetrySucceeds() {
    var fixture = createActiveStream("Retried Streaming Movie", "/test/retried-streaming.mkv");

    try {
      FAKE_TRANSCODE_JOBS.returnTerminalCleanup(
          fixture.streamSessionId(), RuntimeTranscodeCleanup.PENDING);
      libraryManagementService.removeLibrary(fixture.libraryId());

      FAKE_TRANSCODE_JOBS.returnTerminalCleanup(
          fixture.streamSessionId(), RuntimeTranscodeCleanup.COMPLETE);
      deletionRetryWorker.retryPending();

      assertThat(libraryRepository.findById(fixture.libraryId())).isEmpty();
      assertThat(mediaFileRepository.findById(fixture.mediaFileId())).isEmpty();
      assertStreamMissing(fixture.streamSessionId());
      assertThat(intentExists("library_deletion_intent", "library_id", fixture.libraryId()))
          .isFalse();
      assertThat(FAKE_TRANSCODE_JOBS.terminalCleanupAttempts())
          .containsExactly(fixture.streamSessionId(), fixture.streamSessionId());
    } finally {
      cleanupFixture(fixture);
    }
  }

  @Test
  @DisplayName("Should retry parent deletion after cleanup completed before finalization")
  void shouldRetryParentDeletionAfterCleanupCompletedBeforeFinalization() {
    var fixture =
        createActiveStream("Interrupted Parent Deletion", "/test/interrupted-parent-deletion.mkv");

    try {
      deletionTransactions.prepareLibraryDeletion(fixture.libraryId());
      assertThat(deletionTransactions.finalizeLibraryDeletion(fixture.libraryId())).isFalse();
      cleanupWorker.reapPersistedSessions();

      assertThat(libraryRepository.findById(fixture.libraryId())).isPresent();
      assertThat(mediaFileRepository.findById(fixture.mediaFileId())).isPresent();
      assertStreamMissing(fixture.streamSessionId());
      assertThat(intentExists("library_deletion_intent", "library_id", fixture.libraryId()))
          .isTrue();

      deletionRetryWorker.retryPending();

      assertThat(libraryRepository.findById(fixture.libraryId())).isEmpty();
      assertThat(intentExists("library_deletion_intent", "library_id", fixture.libraryId()))
          .isFalse();
    } finally {
      cleanupFixture(fixture);
    }
  }

  @Test
  @DisplayName("Should reject a scan when library deletion is pending")
  void shouldRejectScanWhenLibraryDeletionIsPending() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var libraryId = library.getId();
    deletionTransactions.prepareLibraryDeletion(libraryId);

    assertThatThrownBy(() -> libraryManagementService.scanLibrary(libraryId))
        .isInstanceOf(LibraryNotFoundException.class)
        .hasMessageContaining(libraryId.toString());

    assertThat(intentExists("library_deletion_intent", "library_id", libraryId)).isTrue();
  }

  @Test
  @DisplayName("Should reject a refresh when library deletion is pending")
  void shouldRejectRefreshWhenLibraryDeletionIsPending() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var libraryId = library.getId();
    deletionTransactions.prepareLibraryDeletion(libraryId);

    assertThatThrownBy(() -> libraryManagementService.refreshLibrary(libraryId))
        .isInstanceOf(LibraryNotFoundException.class)
        .hasMessageContaining(libraryId.toString());

    assertThat(intentExists("library_deletion_intent", "library_id", libraryId)).isTrue();
  }

  @Test
  @DisplayName("Should preserve pending deletion while a library mutation is active")
  void shouldPreservePendingDeletionWhileLibraryMutationIsActive() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    deletionTransactions.prepareLibraryDeletion(library.getId());
    library.setStatus(LibraryStatus.SCANNING);
    libraryRepository.saveAndFlush(library);

    deletionRetryWorker.retryPending();

    assertThat(libraryRepository.findById(library.getId())).isPresent();
    assertThat(intentExists("library_deletion_intent", "library_id", library.getId())).isTrue();
  }

  @Test
  @DisplayName("Should retain and retry orphaned media deletion when stream cleanup fails")
  void shouldRetainAndRetryOrphanedMediaDeletionWhenStreamCleanupFails() {
    var fixture =
        createActiveStream("Orphaned Streaming Movie", "/test/missing-orphaned-streaming.mkv");

    try {
      FAKE_TRANSCODE_JOBS.returnTerminalCleanup(
          fixture.streamSessionId(), RuntimeTranscodeCleanup.PENDING);

      orphanedMediaFileCleanupService.cleanupOrphanedFiles(fixture.library());

      assertThat(mediaFileRepository.findById(fixture.mediaFileId())).isPresent();
      assertSourceDeleted(fixture.streamSessionId());
      assertThat(intentExists("media_file_deletion_intent", "media_file_id", fixture.mediaFileId()))
          .isTrue();
      assertThat(deletionTransactions.finalizeMediaFileDeletion(fixture.mediaFileId())).isFalse();

      FAKE_TRANSCODE_JOBS.returnTerminalCleanup(
          fixture.streamSessionId(), RuntimeTranscodeCleanup.COMPLETE);
      deletionRetryWorker.retryPending();

      assertThat(mediaFileRepository.findById(fixture.mediaFileId())).isEmpty();
      assertThat(movieRepository.findById(fixture.movieId())).isEmpty();
      assertThat(libraryRepository.findById(fixture.libraryId())).isPresent();
      assertStreamMissing(fixture.streamSessionId());
      assertThat(intentExists("media_file_deletion_intent", "media_file_id", fixture.mediaFileId()))
          .isFalse();
      assertThat(FAKE_TRANSCODE_JOBS.terminalCleanupAttempts())
          .containsExactly(fixture.streamSessionId(), fixture.streamSessionId());
    } finally {
      cleanupFixture(fixture);
    }
  }

  @Test
  @DisplayName("Should handle stale and conflicting parent deletion work")
  void shouldHandleStaleAndConflictingParentDeletionWork() {
    var missingId = UUID.randomUUID();

    assertThatThrownBy(() -> deletionTransactions.resumeLibraryDeletion(missingId))
        .isInstanceOf(LibraryNotFoundException.class);
    assertThat(deletionTransactions.finalizeLibraryDeletion(missingId)).isFalse();
    assertThat(deletionTransactions.finalizeMediaFileDeletion(missingId)).isFalse();
    assertThat(deletionTransactions.prepareMediaFileDeletions(missingId, Set.of()).targets())
        .isEmpty();
    assertThat(
            deletionTransactions
                .prepareMediaFileDeletions(missingId, Set.of(UUID.randomUUID()))
                .targets())
        .isEmpty();

    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var libraryId = library.getId();
    assertThatThrownBy(() -> deletionTransactions.resumeLibraryDeletion(libraryId))
        .isInstanceOf(LibraryNotFoundException.class);
    assertThat(deletionTransactions.finalizeLibraryDeletion(libraryId)).isFalse();

    var movie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Shared Deletion Target").library(library).build());
    var mediaFiles =
        mediaFileRepository.saveAllAndFlush(
            List.of(
                MediaFile.builder()
                    .libraryId(libraryId)
                    .mediaId(movie.getId())
                    .filepathUri("file:///stale-deletion/first.mkv")
                    .filename("first.mkv")
                    .status(MediaFileStatus.MATCHED)
                    .build(),
                MediaFile.builder()
                    .libraryId(libraryId)
                    .mediaId(movie.getId())
                    .filepathUri("file:///stale-deletion/second.mkv")
                    .filename("second.mkv")
                    .status(MediaFileStatus.MATCHED)
                    .build()));

    deletionTransactions.prepareMediaFileDeletions(
        library.getId(), Set.of(mediaFiles.getFirst().getId()));
    assertThat(deletionTransactions.finalizeMediaFileDeletion(mediaFiles.getFirst().getId()))
        .isTrue();
    assertThat(movieRepository.findById(movie.getId())).isPresent();

    var unmatchedMediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri("file:///stale-deletion/unmatched.mkv")
                .filename("unmatched.mkv")
                .status(MediaFileStatus.UNMATCHED)
                .build());
    deletionTransactions.prepareMediaFileDeletions(
        library.getId(), Set.of(unmatchedMediaFile.getId()));
    assertThat(deletionTransactions.finalizeMediaFileDeletion(unmatchedMediaFile.getId())).isTrue();

    deletionTransactions.prepareLibraryDeletion(library.getId());
    assertThat(
            deletionTransactions
                .prepareMediaFileDeletions(library.getId(), Set.of(mediaFiles.getLast().getId()))
                .targets())
        .isEmpty();
    assertThat(deletionTransactions.resumeMediaFileDeletion(mediaFiles.getLast().getId()).targets())
        .isEmpty();
  }

  @Test
  @DisplayName("Should page beyond the oldest parent deletion intent batch")
  void shouldPageBeyondOldestParentDeletionIntentBatch() {
    var libraries =
        java.util.stream.Stream.generate(LibraryFixtureCreator::buildFakeLibrary)
            .limit(51)
            .toList();
    libraryRepository.saveAllAndFlush(libraries);
    libraries.forEach(library -> deletionTransactions.prepareLibraryDeletion(library.getId()));

    var mediaLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var mediaFiles =
        java.util.stream.IntStream.range(0, 51)
            .mapToObj(
                index ->
                    MediaFile.builder()
                        .libraryId(mediaLibrary.getId())
                        .filepathUri("file:///parent-deletion-batch/" + index + ".mkv")
                        .filename(index + ".mkv")
                        .status(MediaFileStatus.UNMATCHED)
                        .build())
            .toList();
    mediaFileRepository.saveAllAndFlush(mediaFiles);
    deletionTransactions.prepareMediaFileDeletions(
        mediaLibrary.getId(),
        mediaFiles.stream().map(MediaFile::getId).collect(java.util.stream.Collectors.toSet()));

    var firstLibraries = mediaParentDeletionRepository.findLibraryDeletionIntents(50);
    var nextLibraries =
        mediaParentDeletionRepository.findLibraryDeletionIntentsAfter(firstLibraries.getLast(), 50);
    var firstMedia = mediaParentDeletionRepository.findStandaloneMediaFileDeletionIntents(50);
    var nextMedia =
        mediaParentDeletionRepository.findStandaloneMediaFileDeletionIntentsAfter(
            firstMedia.getLast(), 50);

    assertThat(firstLibraries).hasSize(50);
    assertThat(nextLibraries).hasSize(1);
    assertThat(firstMedia).hasSize(50);
    assertThat(nextMedia).hasSize(1);
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
  @DisplayName("Should throw LibraryRefreshInProgressException when library is refreshing")
  void shouldThrowLibraryRefreshInProgressExceptionWhenLibraryIsRefreshing() {
    var libraryToSave = LibraryFixtureCreator.buildFakeLibrary();
    libraryToSave.setStatus(LibraryStatus.REFRESHING);
    var library = libraryRepository.saveAndFlush(libraryToSave);

    var libraryId = library.getId();

    assertThatThrownBy(() -> libraryManagementService.removeLibrary(libraryId))
        .isInstanceOf(LibraryRefreshInProgressException.class)
        .hasMessageContaining(libraryId.toString());

    var persisted = libraryRepository.findById(libraryId).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(LibraryStatus.REFRESHING);
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

  private boolean intentExists(String tableName, String idColumnName, UUID id) {
    var table = DSL.table(DSL.name(tableName));
    var idColumn = DSL.field(DSL.name(idColumnName), UUID.class);
    return dsl.fetchExists(DSL.selectOne().from(table).where(idColumn.eq(id)));
  }

  private ActiveStreamFixture createActiveStream(String title, String filepathUri) {
    var identity = authTestSupport.createIdentity();
    sessionScopeService.selectHousehold(
        identity.account().getId(), identity.session().getId(), identity.household().getId());
    sessionScopeService.selectProfile(
        identity.account().getId(), identity.session().getId(), identity.profile().getId());
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie = movieRepository.saveAndFlush(Movie.builder().title(title).library(library).build());
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .mediaId(movie.getId())
                .filepathUri(filepathUri)
                .filename(filepathUri.substring(filepathUri.lastIndexOf('/') + 1))
                .status(MediaFileStatus.MATCHED)
                .build());
    var sourceIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(identity)));
    var session =
        playbackSessionCreationService.create(
            CreatePlaybackSessionCommand.builder()
                .mediaFileId(mediaFile.getId())
                .options(
                    StreamingOptions.builder()
                        .quality(VideoQuality.AUTO)
                        .supportedCodecs(List.of("h264"))
                        .build())
                .sourceIdentity(sourceIdentity)
                .build());
    return ActiveStreamFixture.builder()
        .identity(identity)
        .library(library)
        .movieId(movie.getId())
        .mediaFileId(mediaFile.getId())
        .streamSessionId(session.sessionId())
        .build();
  }

  private void cleanupFixture(ActiveStreamFixture fixture) {
    FAKE_TRANSCODE_JOBS.returnTerminalCleanup(
        fixture.streamSessionId(), RuntimeTranscodeCleanup.COMPLETE);
    deletionRetryWorker.retryPending();
    streamingService.terminateRuntime(fixture.streamSessionId());
    dsl.deleteFrom(STREAM_SESSION).where(STREAM_SESSION.ID.eq(fixture.streamSessionId())).execute();
    authTestSupport.deleteIdentity(fixture.identity());
  }

  private void assertSourceDeleted(UUID streamSessionId) {
    assertThat(
            dsl.select(STREAM_SESSION.STATUS, STREAM_SESSION.TERMINAL_REASON)
                .from(STREAM_SESSION)
                .where(STREAM_SESSION.ID.eq(streamSessionId))
                .fetchOne())
        .satisfies(
            stored -> {
              assertThat(stored.value1()).isEqualTo(StreamSessionStatus.TERMINATING);
              assertThat(stored.value2()).isEqualTo(StreamSessionTerminalReason.SOURCE_DELETED);
            });
  }

  private void assertStreamMissing(UUID streamSessionId) {
    assertThat(
            dsl.fetchExists(
                DSL.selectOne().from(STREAM_SESSION).where(STREAM_SESSION.ID.eq(streamSessionId))))
        .isFalse();
  }

  @Builder
  private record ActiveStreamFixture(
      TestIdentity identity,
      Library library,
      UUID movieId,
      UUID mediaFileId,
      UUID streamSessionId) {

    private UUID libraryId() {
      return library.getId();
    }
  }
}
