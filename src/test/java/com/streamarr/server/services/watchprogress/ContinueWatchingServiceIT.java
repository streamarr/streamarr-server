package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Continue Watching Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContinueWatchingServiceIT extends AbstractIntegrationTest {

  @Autowired private ContinueWatchingService continueWatchingService;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private DSLContext dsl;

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant MOVIE_ACTIVITY_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EPISODE_ACTIVITY_AT = Instant.parse("2026-02-01T00:00:00Z");

  private Library movieLibrary;
  private Library seriesLibrary;
  private Movie inProgressMovie;
  private UUID inProgressMovieFileId;
  private Movie watchedMovie;
  private Movie unwatchedMovie;
  private Episode inProgressEpisode;
  private UUID inProgressEpisodeFileId;

  @BeforeAll
  void setup() {
    // TODO(#163): replace this pre-delete with a per-class user ID once auth lands.
    dsl.deleteFrom(Tables.SESSION_PROGRESS)
        .where(Tables.SESSION_PROGRESS.USER_ID.eq(USER_ID))
        .execute();
    dsl.deleteFrom(Tables.WATCH_HISTORY)
        .where(Tables.WATCH_HISTORY.USER_ID.eq(USER_ID))
        .execute();

    movieLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    seriesLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    // In-progress movie: has session progress
    inProgressMovie = createMovieWithProgress("In Progress Movie", 1800, 25.0, 7200);
    inProgressMovieFileId = inProgressMovie.getFiles().iterator().next().getId();

    // Watched movie: has watch_history (should be excluded)
    watchedMovie = createMovieWithFile("Watched Movie");
    watchHistoryRepository.saveAndFlush(
        WatchHistory.builder()
            .userId(USER_ID)
            .collectableId(watchedMovie.getId())
            .watchedAt(Instant.now())
            .durationSeconds(7200)
            .build());

    // Unwatched movie: no activity (should be excluded)
    unwatchedMovie = createMovieWithFile("Unwatched Movie");

    // In-progress episode: has session progress
    inProgressEpisode = createEpisodeWithProgress("In Progress Episode", 900, 15.0, 3600);
    inProgressEpisodeFileId = inProgressEpisode.getFiles().iterator().next().getId();

    // Pin LAST_MODIFIED_ON explicitly so ordering is deterministic regardless of clock resolution.
    pinSessionProgressTimestamp(inProgressMovieFileId, MOVIE_ACTIVITY_AT);
    pinSessionProgressTimestamp(inProgressEpisodeFileId, EPISODE_ACTIVITY_AT);
  }

  private void pinSessionProgressTimestamp(UUID mediaFileId, Instant timestamp) {
    dsl.update(Tables.SESSION_PROGRESS)
        .set(Tables.SESSION_PROGRESS.LAST_MODIFIED_ON, timestamp.atOffset(ZoneOffset.UTC))
        .where(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(mediaFileId))
        .execute();
  }

  private Movie createMovieWithFile(String title) {
    var file =
        MediaFile.builder()
            .libraryId(movieLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(title.toLowerCase().replace(' ', '-') + ".mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build();

    return movieRepository.saveAndFlush(
        Movie.builder()
            .title(title)
            .titleSort(title)
            .files(Set.of(file))
            .library(movieLibrary)
            .build());
  }

  private Movie createMovieWithProgress(
      String title, int positionSeconds, double percentComplete, int durationSeconds) {
    var movie = createMovieWithFile(title);
    var fileId = movie.getFiles().iterator().next().getId();

    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .userId(USER_ID)
            .mediaFileId(fileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .build());

    return movie;
  }

  private Episode createEpisodeWithProgress(
      String title, int positionSeconds, double percentComplete, int durationSeconds) {
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Test Series")
                .titleSort("Test Series")
                .library(seriesLibrary)
                .build());

    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(seriesLibrary).build());

    var file =
        MediaFile.builder()
            .libraryId(seriesLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(title.toLowerCase().replace(' ', '-') + ".mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build();

    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .episodeNumber(1)
                .title(title)
                .season(season)
                .library(seriesLibrary)
                .files(Set.of(file))
                .build());

    var fileId = episode.getFiles().iterator().next().getId();

    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .userId(USER_ID)
            .mediaFileId(fileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .build());

    return episode;
  }

  @Nested
  @DisplayName("getContinueWatching")
  class GetContinueWatchingTests {

    @Test
    @DisplayName("Should return exactly the in-progress movie and episode when querying")
    void shouldReturnExactlyTheInProgressMovieAndEpisodeWhenQuerying() {
      var results = continueWatchingService.getContinueWatching(20);

      assertThat(results)
          .extracting(BaseCollectable::getId)
          .containsExactlyInAnyOrder(inProgressMovie.getId(), inProgressEpisode.getId());
    }

    @Test
    @DisplayName("Should not include the watched movie when querying")
    void shouldNotIncludeTheWatchedMovieWhenQuerying() {
      var results = continueWatchingService.getContinueWatching(20);

      assertThat(results).extracting(BaseCollectable::getId).doesNotContain(watchedMovie.getId());
    }

    @Test
    @DisplayName("Should not include the unwatched movie with no session progress when querying")
    void shouldNotIncludeTheUnwatchedMovieWithNoSessionProgressWhenQuerying() {
      var results = continueWatchingService.getContinueWatching(20);

      assertThat(results).extracting(BaseCollectable::getId).doesNotContain(unwatchedMovie.getId());
    }

    @Test
    @DisplayName("Should return empty list when called with limit zero")
    void shouldReturnEmptyListWhenCalledWithLimitZero() {
      var results = continueWatchingService.getContinueWatching(0);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Should return only the most recent item when called with limit one")
    void shouldReturnOnlyTheMostRecentItemWhenCalledWithLimitOne() {
      var results = continueWatchingService.getContinueWatching(1);

      assertThat(results)
          .extracting(BaseCollectable::getId)
          .containsExactly(inProgressEpisode.getId());
    }

    @Test
    @DisplayName(
        "Should order items by session progress last modified descending when querying"
            + " with pinned timestamps")
    void shouldOrderItemsBySessionProgressLastModifiedDescendingWhenQueryingWithPinnedTimestamps() {
      var results = continueWatchingService.getContinueWatching(20);

      // EPISODE_ACTIVITY_AT (Feb) > MOVIE_ACTIVITY_AT (Jan) — episode must come first.
      assertThat(results)
          .extracting(BaseCollectable::getId)
          .containsExactly(inProgressEpisode.getId(), inProgressMovie.getId());
    }
  }
}
