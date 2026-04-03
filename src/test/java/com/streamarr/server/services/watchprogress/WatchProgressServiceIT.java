package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SaveProgressCommand;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Watch Progress Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchProgressServiceIT extends AbstractIntegrationTest {

  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private WatchProgressService watchProgressService;
  @Autowired private EntityManager entityManager;
  @Autowired private AuditorAware<UUID> auditorAware;

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Library library;

  @BeforeAll
  void setup() {
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
  }

  private record MovieWithFile(Movie movie, UUID mediaFileId) {}

  private MovieWithFile createMovieWithFile() {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("movie-" + UUID.randomUUID() + ".mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build();

    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Test Movie " + UUID.randomUUID())
                .files(Set.of(file))
                .library(library)
                .build());

    var mediaFileId = movie.getFiles().iterator().next().getId();
    return new MovieWithFile(movie, mediaFileId);
  }

  @Test
  @Transactional
  @DisplayName("Should persist and retrieve watch progress when saved")
  void shouldPersistAndRetrieveWatchProgressWhenSaved() {
    var fixture = createMovieWithFile();

    var saved =
        sessionProgressRepository.save(
            SessionProgress.builder()
                .userId(USER_ID)
                .mediaFileId(fixture.mediaFileId())
                .positionSeconds(3600)
                .percentComplete(50.0)
                .durationSeconds(7200)
                .build());

    entityManager.flush();
    entityManager.clear();

    var retrieved =
        sessionProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId());

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getPositionSeconds()).isEqualTo(3600);
    assertThat(retrieved.get().getPercentComplete()).isEqualTo(50.0);
    assertThat(retrieved.get().getDurationSeconds()).isEqualTo(7200);
    assertThat(retrieved.get().getId()).isEqualTo(saved.getId());
  }

  @Test
  @Transactional
  @DisplayName("Should update existing progress when same user and media file")
  void shouldUpdateExistingProgressWhenSameUserAndMediaFile() {
    var fixture = createMovieWithFile();

    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .userId(USER_ID)
            .mediaFileId(fixture.mediaFileId())
            .positionSeconds(300)
            .percentComplete(10.0)
            .durationSeconds(3000)
            .build());

    entityManager.clear();

    var existing =
        sessionProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId())
            .orElseThrow();
    existing.setPositionSeconds(600);
    existing.setPercentComplete(20.0);
    sessionProgressRepository.saveAndFlush(existing);

    entityManager.clear();

    var all =
        sessionProgressRepository.findByUserIdAndMediaFileIdIn(
            USER_ID, Set.of(fixture.mediaFileId()));
    assertThat(all).hasSize(1);
    assertThat(all.getFirst().getPositionSeconds()).isEqualTo(600);
  }

  @Test
  @Transactional
  @DisplayName("Should cascade delete watch progress when movie removed")
  void shouldCascadeDeleteWatchProgressWhenMovieRemoved() {
    var fixture = createMovieWithFile();

    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .userId(USER_ID)
            .mediaFileId(fixture.mediaFileId())
            .positionSeconds(1800)
            .percentComplete(50.0)
            .durationSeconds(3600)
            .build());

    assertThat(sessionProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId()))
        .isPresent();

    movieRepository.deleteById(fixture.movie().getId());
    movieRepository.flush();
    entityManager.clear();

    assertThat(sessionProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId()))
        .isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("Should delete watch progress when resetting movie through service")
  void shouldDeleteWatchProgressWhenResettingMovieThroughService() {
    var fixture = createMovieWithFile();

    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .userId(USER_ID)
            .mediaFileId(fixture.mediaFileId())
            .positionSeconds(2400)
            .percentComplete(66.7)
            .durationSeconds(3600)
            .build());

    entityManager.flush();
    entityManager.clear();

    watchProgressService.resetProgress(USER_ID, fixture.movie().getId());

    entityManager.flush();
    entityManager.clear();

    assertThat(sessionProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId()))
        .isEmpty();
  }

  @Nested
  @DisplayName("jOOQ Upsert Guard")
  class JooqUpsertGuard {

    @Test
    @Transactional
    @DisplayName("Should insert new progress when no existing row")
    void shouldInsertNewProgressWhenNoExistingRow() {
      var fixture = createMovieWithFile();

      var result =
          sessionProgressRepository.upsertProgress(
              SaveProgressCommand.UpdateProgress.builder()
                  .userId(USER_ID)
                  .mediaFileId(fixture.mediaFileId())
                  .positionSeconds(300)
                  .percentComplete(25.0)
                  .durationSeconds(1200)
                  .build());

      assertThat(result).isTrue();

      entityManager.clear();

      var progress =
          sessionProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId())
              .orElseThrow();
      assertThat(progress.getPositionSeconds()).isEqualTo(300);
      assertThat(progress.getPercentComplete()).isEqualTo(25.0);
      assertThat(progress.getDurationSeconds()).isEqualTo(1200);
      assertThat(progress.getLastPlayedAt()).isNull();
    }

    @Test
    @Transactional
    @DisplayName("Should overwrite progress when existing row is unwatched on upsert")
    void shouldOverwriteProgressWhenExistingRowIsUnwatchedOnUpsert() {
      var fixture = createMovieWithFile();

      sessionProgressRepository.saveAndFlush(
          SessionProgress.builder()
              .userId(USER_ID)
              .mediaFileId(fixture.mediaFileId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build());

      entityManager.clear();

      var result =
          sessionProgressRepository.upsertProgress(
              SaveProgressCommand.UpdateProgress.builder()
                  .userId(USER_ID)
                  .mediaFileId(fixture.mediaFileId())
                  .positionSeconds(600)
                  .percentComplete(50.0)
                  .durationSeconds(1200)
                  .build());

      assertThat(result).isTrue();

      entityManager.clear();

      var progress =
          sessionProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId())
              .orElseThrow();
      assertThat(progress.getPositionSeconds()).isEqualTo(600);
      assertThat(progress.getPercentComplete()).isEqualTo(50.0);
    }

    @Test
    @Transactional
    @DisplayName("Should not overwrite progress when existing row is watched on upsert")
    void shouldNotOverwriteProgressWhenExistingRowIsWatchedOnUpsert() {
      var fixture = createMovieWithFile();
      var watchedAt = Instant.now();

      sessionProgressRepository.saveAndFlush(
          SessionProgress.builder()
              .userId(USER_ID)
              .mediaFileId(fixture.mediaFileId())
              .positionSeconds(0)
              .percentComplete(100.0)
              .durationSeconds(1200)
              .lastPlayedAt(watchedAt)
              .build());

      entityManager.clear();

      var result =
          sessionProgressRepository.upsertProgress(
              SaveProgressCommand.UpdateProgress.builder()
                  .userId(USER_ID)
                  .mediaFileId(fixture.mediaFileId())
                  .positionSeconds(300)
                  .percentComplete(25.0)
                  .durationSeconds(1200)
                  .build());

      assertThat(result).isFalse();

      entityManager.clear();

      var progress =
          sessionProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId())
              .orElseThrow();
      assertThat(progress.getPositionSeconds()).isZero();
      assertThat(progress.getPercentComplete()).isEqualTo(100.0);
      assertThat(progress.getLastPlayedAt()).isNotNull();
    }

    @Test
    @Transactional
    @DisplayName("Should populate audit fields when inserting new row on upsert")
    void shouldPopulateAuditFieldsWhenInsertingNewRowOnUpsert() {
      var fixture = createMovieWithFile();

      sessionProgressRepository.upsertProgress(
          SaveProgressCommand.UpdateProgress.builder()
              .userId(USER_ID)
              .mediaFileId(fixture.mediaFileId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build());

      entityManager.clear();

      var progress =
          sessionProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId())
              .orElseThrow();
      var expectedAuditor = auditorAware.getCurrentAuditor().orElseThrow();

      assertThat(progress.getCreatedBy()).isEqualTo(expectedAuditor);
      assertThat(progress.getLastModifiedBy()).isEqualTo(expectedAuditor);
    }

    @Test
    @Transactional
    @DisplayName("Should update audit fields when existing row conflicts on upsert")
    void shouldUpdateAuditFieldsWhenExistingRowConflictsOnUpsert() {
      var fixture = createMovieWithFile();

      sessionProgressRepository.upsertProgress(
          SaveProgressCommand.UpdateProgress.builder()
              .userId(USER_ID)
              .mediaFileId(fixture.mediaFileId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build());

      entityManager.clear();

      sessionProgressRepository.upsertProgress(
          SaveProgressCommand.UpdateProgress.builder()
              .userId(USER_ID)
              .mediaFileId(fixture.mediaFileId())
              .positionSeconds(600)
              .percentComplete(50.0)
              .durationSeconds(1200)
              .build());

      entityManager.clear();

      var progress =
          sessionProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId())
              .orElseThrow();
      var expectedAuditor = auditorAware.getCurrentAuditor().orElseThrow();

      assertThat(progress.getLastModifiedBy()).isEqualTo(expectedAuditor);
      assertThat(progress.getPositionSeconds()).isEqualTo(600);
    }
  }

  @Nested
  @DisplayName("jOOQ Delete Guard")
  class JooqDeleteGuard {

    @Test
    @Transactional
    @DisplayName("Should delete unwatched progress when deleteIfNotWatched called")
    void shouldDeleteUnwatchedProgressWhenDeleteIfNotWatchedCalled() {
      var fixture = createMovieWithFile();

      sessionProgressRepository.saveAndFlush(
          SessionProgress.builder()
              .userId(USER_ID)
              .mediaFileId(fixture.mediaFileId())
              .positionSeconds(100)
              .percentComplete(5.0)
              .durationSeconds(2000)
              .build());

      entityManager.clear();

      var result = sessionProgressRepository.deleteIfNotWatched(USER_ID, fixture.mediaFileId());

      assertThat(result).isTrue();

      entityManager.clear();

      assertThat(sessionProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId()))
          .isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Should not delete watched progress when deleteIfNotWatched called")
    void shouldNotDeleteWatchedProgressWhenDeleteIfNotWatchedCalled() {
      var fixture = createMovieWithFile();

      sessionProgressRepository.saveAndFlush(
          SessionProgress.builder()
              .userId(USER_ID)
              .mediaFileId(fixture.mediaFileId())
              .positionSeconds(0)
              .percentComplete(100.0)
              .durationSeconds(2000)
              .lastPlayedAt(Instant.now())
              .build());

      entityManager.clear();

      var result = sessionProgressRepository.deleteIfNotWatched(USER_ID, fixture.mediaFileId());

      assertThat(result).isFalse();

      entityManager.clear();

      assertThat(sessionProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId()))
          .isPresent();
    }
  }
}
