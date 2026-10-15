package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.TestIdentityConstants;
import com.streamarr.server.support.security.WithProfileContext;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Watch Progress Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@WithProfileContext
class SessionProgressServiceIT extends AbstractIntegrationTest {

  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private WatchStatusService watchStatusService;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private AuditColumnFixtures auditColumns;

  private static final Instant EARLIER_REPORT_AT = Instant.parse("2026-01-01T00:00:00Z");

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;

  private Library library;

  @BeforeAll
  void setup() {
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
  }

  private record MovieWithFile(Movie movie, UUID mediaFileId) {}

  private MovieWithFile createMovieWithFile() {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("movie-" + UUID.randomUUID() + ".mkv")
            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
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

  private SaveWatchProgress.SaveWatchProgressBuilder upsertBuilder(
      UUID sessionId, UUID mediaFileId) {
    return SaveWatchProgress.builder()
        .sessionId(sessionId)
        .profileId(profileId)
        .mediaFileId(mediaFileId);
  }

  @Test
  @Transactional
  @DisplayName("Should persist and retrieve watch progress when saved")
  void shouldPersistAndRetrieveWatchProgressWhenSaved() {
    var fixture = createMovieWithFile();
    var sessionId = UUID.randomUUID();

    var saved =
        sessionProgressRepository.save(
            progressBuilder(profileId, fixture.mediaFileId())
                .sessionId(sessionId)
                .positionSeconds(3600)
                .percentComplete(50.0)
                .durationSeconds(7200)
                .build());

    entityManager.flush();
    entityManager.clear();

    var retrieved = sessionProgressRepository.findBySessionId(sessionId);

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getPositionSeconds()).isEqualTo(3600);
    assertThat(retrieved.get().getPercentComplete()).isEqualTo(50.0);
    assertThat(retrieved.get().getDurationSeconds()).isEqualTo(7200);
    assertThat(retrieved.get().getId()).isEqualTo(saved.getId());
  }

  @Test
  @Transactional
  @DisplayName("Should cascade delete watch progress when movie removed")
  void shouldCascadeDeleteWatchProgressWhenMovieRemoved() {
    var fixture = createMovieWithFile();

    sessionProgressRepository.saveAndFlush(
        progressBuilder(profileId, fixture.mediaFileId())
            .positionSeconds(1800)
            .percentComplete(50.0)
            .durationSeconds(3600)
            .build());

    assertThat(
            sessionProgressRepository.findByProfileIdAndMediaFileIdIn(
                profileId, Set.of(fixture.mediaFileId())))
        .hasSize(1);

    movieRepository.deleteById(fixture.movie().getId());
    movieRepository.flush();
    entityManager.clear();

    assertThat(
            sessionProgressRepository.findByProfileIdAndMediaFileIdIn(
                profileId, Set.of(fixture.mediaFileId())))
        .isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("Should delete watch progress when resetting movie through service")
  void shouldDeleteWatchProgressWhenResettingMovieThroughService() {
    var fixture = createMovieWithFile();

    sessionProgressRepository.saveAndFlush(
        progressBuilder(profileId, fixture.mediaFileId())
            .positionSeconds(2400)
            .percentComplete(66.7)
            .durationSeconds(3600)
            .build());

    entityManager.flush();
    entityManager.clear();

    watchStatusService.markUnwatched(profileId, fixture.movie().getId());

    entityManager.flush();
    entityManager.clear();

    assertThat(
            sessionProgressRepository.findByProfileIdAndMediaFileIdIn(
                profileId, Set.of(fixture.mediaFileId())))
        .isEmpty();
  }

  @Nested
  @DisplayName("jOOQ Upsert")
  class JooqUpsert {

    @Test
    @Transactional
    @DisplayName("Should insert new progress when no existing row")
    void shouldInsertNewProgressWhenNoExistingRow() {
      var fixture = createMovieWithFile();
      var sessionId = UUID.randomUUID();

      sessionProgressRepository.upsertProgress(
          upsertBuilder(sessionId, fixture.mediaFileId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build());

      entityManager.clear();

      var progress = sessionProgressRepository.findBySessionId(sessionId).orElseThrow();
      assertThat(progress.getPositionSeconds()).isEqualTo(300);
      assertThat(progress.getPercentComplete()).isEqualTo(25.0);
      assertThat(progress.getDurationSeconds()).isEqualTo(1200);
    }

    @Test
    @Transactional
    @DisplayName("Should overwrite progress when existing row exists for same session on upsert")
    void shouldOverwriteProgressWhenExistingRowExistsForSameSessionOnUpsert() {
      var fixture = createMovieWithFile();
      var sessionId = UUID.randomUUID();

      sessionProgressRepository.upsertProgress(
          upsertBuilder(sessionId, fixture.mediaFileId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build());

      entityManager.clear();

      var changed =
          sessionProgressRepository.upsertProgress(
              upsertBuilder(sessionId, fixture.mediaFileId())
                  .positionSeconds(600)
                  .percentComplete(50.0)
                  .durationSeconds(1200)
                  .build());

      entityManager.clear();

      var progress = sessionProgressRepository.findBySessionId(sessionId).orElseThrow();
      assertThat(changed).isTrue();
      assertThat(progress.getPositionSeconds()).isEqualTo(600);
      assertThat(progress.getPercentComplete()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Should keep the row unchanged when a repeated report carries the same position")
    void shouldKeepRowUnchangedWhenRepeatedReportCarriesTheSamePosition() {
      var fixture = createMovieWithFile();
      var sessionId = UUID.randomUUID();
      var report =
          upsertBuilder(sessionId, fixture.mediaFileId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build();

      try {
        upsertInTransaction(report);
        auditColumns.overwriteLastModifiedOn(
            SESSION_PROGRESS, SESSION_PROGRESS.SESSION_ID.eq(sessionId), EARLIER_REPORT_AT);

        var changed =
            transactionTemplate.execute(_ -> sessionProgressRepository.upsertProgress(report));

        var progress = sessionProgressRepository.findBySessionId(sessionId).orElseThrow();
        assertThat(changed).isFalse();
        assertThat(progress.getLastModifiedOn()).isEqualTo(EARLIER_REPORT_AT);
      } finally {
        deleteProgressAndMovie(sessionId, fixture);
      }
    }

    @Test
    @DisplayName("Should update the row when a repeated report carries a new duration")
    void shouldUpdateRowWhenRepeatedReportCarriesANewDuration() {
      var fixture = createMovieWithFile();
      var sessionId = UUID.randomUUID();
      var report =
          upsertBuilder(sessionId, fixture.mediaFileId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200);

      try {
        upsertInTransaction(report.build());
        auditColumns.overwriteLastModifiedOn(
            SESSION_PROGRESS, SESSION_PROGRESS.SESSION_ID.eq(sessionId), EARLIER_REPORT_AT);

        var changed =
            transactionTemplate.execute(
                _ ->
                    sessionProgressRepository.upsertProgress(
                        report.percentComplete(20.0).durationSeconds(1500).build()));

        var progress = sessionProgressRepository.findBySessionId(sessionId).orElseThrow();
        assertThat(changed).isTrue();
        assertThat(progress.getDurationSeconds()).isEqualTo(1500);
        assertThat(progress.getLastModifiedOn()).isAfter(EARLIER_REPORT_AT);
      } finally {
        deleteProgressAndMovie(sessionId, fixture);
      }
    }

    // A test-managed transaction begins before the signed-in Profile is installed, so these
    // writes run in transactions of their own.
    @Test
    @DisplayName("Should name the signed-in account when an upsert inserts a new row")
    void shouldNameSignedInAccountWhenAnUpsertInsertsANewRow() {
      var fixture = createMovieWithFile();
      var sessionId = UUID.randomUUID();

      try {
        upsertInTransaction(
            upsertBuilder(sessionId, fixture.mediaFileId())
                .positionSeconds(300)
                .percentComplete(25.0)
                .durationSeconds(1200)
                .build());

        var progress = sessionProgressRepository.findBySessionId(sessionId).orElseThrow();
        assertThat(progress.getCreatedBy()).isEqualTo(TestIdentityConstants.ACCOUNT_ID);
        assertThat(progress.getLastModifiedBy()).isEqualTo(TestIdentityConstants.ACCOUNT_ID);
      } finally {
        deleteProgressAndMovie(sessionId, fixture);
      }
    }

    @Test
    @DisplayName(
        "Should keep the creator and name the signed-in account when an upsert updates a row")
    void shouldKeepTheCreatorAndNameTheSignedInAccountWhenAnUpsertUpdatesARow() {
      var fixture = createMovieWithFile();
      var sessionId = UUID.randomUUID();

      try {
        upsertInTransaction(
            upsertBuilder(sessionId, fixture.mediaFileId())
                .positionSeconds(300)
                .percentComplete(25.0)
                .durationSeconds(1200)
                .build());
        // An empty stored creator differs from the signed-in account, so the assertions can tell
        // a kept creator from one the update overwrote.
        auditColumns.clearActors(SESSION_PROGRESS, SESSION_PROGRESS.SESSION_ID.eq(sessionId));

        upsertInTransaction(
            upsertBuilder(sessionId, fixture.mediaFileId())
                .positionSeconds(600)
                .percentComplete(50.0)
                .durationSeconds(1200)
                .build());

        var progress = sessionProgressRepository.findBySessionId(sessionId).orElseThrow();
        assertThat(progress.getPositionSeconds()).isEqualTo(600);
        assertThat(progress.getCreatedBy()).isNull();
        assertThat(progress.getLastModifiedBy()).isEqualTo(TestIdentityConstants.ACCOUNT_ID);
      } finally {
        deleteProgressAndMovie(sessionId, fixture);
      }
    }

    private void upsertInTransaction(SaveWatchProgress progress) {
      transactionTemplate.executeWithoutResult(
          _ -> sessionProgressRepository.upsertProgress(progress));
    }

    private void deleteProgressAndMovie(UUID sessionId, MovieWithFile fixture) {
      sessionProgressRepository.deleteBySessionId(sessionId);
      movieRepository.deleteById(fixture.movie().getId());
    }
  }

  @Nested
  @DisplayName("jOOQ Delete")
  class JooqDelete {

    @Test
    @Transactional
    @DisplayName("Should delete progress when deleteBySessionId called")
    void shouldDeleteProgressWhenDeleteBySessionIdCalled() {
      var fixture = createMovieWithFile();
      var sessionId = UUID.randomUUID();

      sessionProgressRepository.saveAndFlush(
          progressBuilder(profileId, fixture.mediaFileId())
              .sessionId(sessionId)
              .positionSeconds(100)
              .percentComplete(5.0)
              .durationSeconds(2000)
              .build());

      entityManager.clear();

      var deleted = sessionProgressRepository.deleteBySessionId(sessionId);
      var deletedAgain = sessionProgressRepository.deleteBySessionId(sessionId);

      entityManager.clear();

      assertThat(sessionProgressRepository.findBySessionId(sessionId)).isEmpty();
      assertThat(deleted).isEqualTo(1);
      assertThat(deletedAgain).isZero();
    }

    @Test
    @Transactional
    @DisplayName("Should count the profile's progress rows deleted for the media files")
    void shouldCountTheProfilesProgressRowsDeletedForTheMediaFiles() {
      var fixture = createMovieWithFile();
      sessionProgressRepository.saveAndFlush(
          progressBuilder(profileId, fixture.mediaFileId()).positionSeconds(100).build());
      sessionProgressRepository.saveAndFlush(
          progressBuilder(profileId, fixture.mediaFileId()).positionSeconds(200).build());
      entityManager.clear();

      var deleted =
          sessionProgressRepository.deleteByProfileIdAndMediaFileIds(
              profileId, Set.of(fixture.mediaFileId()));
      var deletedAgain =
          sessionProgressRepository.deleteByProfileIdAndMediaFileIds(
              profileId, Set.of(fixture.mediaFileId()));

      assertThat(deleted).isEqualTo(2);
      assertThat(deletedAgain).isZero();
    }
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }
}
