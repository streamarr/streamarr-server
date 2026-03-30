package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import jakarta.persistence.EntityManager;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Watch Progress Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchProgressServiceIT extends AbstractIntegrationTest {

  @Autowired private WatchProgressRepository watchProgressRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private WatchProgressService watchProgressService;
  @Autowired private EntityManager entityManager;

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
  @DisplayName("Should persist and retrieve watch progress")
  void shouldPersistAndRetrieveWatchProgress() {
    var fixture = createMovieWithFile();

    var saved =
        watchProgressRepository.save(
            WatchProgress.builder()
                .userId(USER_ID)
                .mediaFileId(fixture.mediaFileId())
                .positionSeconds(3600)
                .percentComplete(50.0)
                .durationSeconds(7200)
                .build());

    entityManager.flush();
    entityManager.clear();

    var retrieved =
        watchProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId());

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getPositionSeconds()).isEqualTo(3600);
    assertThat(retrieved.get().getPercentComplete()).isEqualTo(50.0);
    assertThat(retrieved.get().getDurationSeconds()).isEqualTo(7200);
    assertThat(retrieved.get().getId()).isEqualTo(saved.getId());
  }

  @Test
  @Transactional
  @DisplayName("Should enforce unique constraint on user and media file")
  void shouldEnforceUniqueConstraintOnUserAndMediaFile() {
    var fixture = createMovieWithFile();

    watchProgressRepository.saveAndFlush(
        WatchProgress.builder()
            .userId(USER_ID)
            .mediaFileId(fixture.mediaFileId())
            .positionSeconds(300)
            .percentComplete(10.0)
            .durationSeconds(3000)
            .build());

    entityManager.clear();

    var existing =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId())
            .orElseThrow();
    existing.setPositionSeconds(600);
    existing.setPercentComplete(20.0);
    watchProgressRepository.saveAndFlush(existing);

    entityManager.clear();

    var all =
        watchProgressRepository.findByUserIdAndMediaFileIdIn(
            USER_ID, Set.of(fixture.mediaFileId()));
    assertThat(all).hasSize(1);
    assertThat(all.getFirst().getPositionSeconds()).isEqualTo(600);
  }

  @Test
  @Transactional
  @DisplayName("Should cascade delete watch progress when movie removed")
  void shouldCascadeDeleteWatchProgressWhenMovieRemoved() {
    var fixture = createMovieWithFile();

    watchProgressRepository.saveAndFlush(
        WatchProgress.builder()
            .userId(USER_ID)
            .mediaFileId(fixture.mediaFileId())
            .positionSeconds(1800)
            .percentComplete(50.0)
            .durationSeconds(3600)
            .build());

    assertThat(watchProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId()))
        .isPresent();

    entityManager
        .createNativeQuery("DELETE FROM base_collectable WHERE id = :id")
        .setParameter("id", fixture.movie().getId())
        .executeUpdate();
    entityManager.flush();
    entityManager.clear();

    var count =
        ((Number)
                entityManager
                    .createNativeQuery(
                        "SELECT COUNT(*) FROM watch_progress WHERE user_id = :uid AND media_file_id = :mfid")
                    .setParameter("uid", USER_ID)
                    .setParameter("mfid", fixture.mediaFileId())
                    .getSingleResult())
            .longValue();
    assertThat(count).isZero();
  }

  @Test
  @Transactional
  @DisplayName("Should reset progress for movie via service")
  void shouldResetProgressForMovieViaService() {
    var fixture = createMovieWithFile();

    watchProgressRepository.saveAndFlush(
        WatchProgress.builder()
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

    assertThat(watchProgressRepository.findByUserIdAndMediaFileId(USER_ID, fixture.mediaFileId()))
        .isEmpty();
  }
}
