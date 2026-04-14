package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
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
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
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

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Library movieLibrary;
  private Library seriesLibrary;
  private Movie inProgressMovie;
  private Episode inProgressEpisode;

  @BeforeAll
  void setup() {
    movieLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    seriesLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    // In-progress movie: has session progress
    inProgressMovie = createMovieWithProgress("In Progress Movie", 1800, 25.0, 7200);

    // Watched movie: has watch_history (should be excluded)
    var watchedMovie = createMovieWithFile("Watched Movie");
    watchHistoryRepository.saveAndFlush(
        WatchHistory.builder()
            .userId(USER_ID)
            .collectableId(watchedMovie.getId())
            .watchedAt(Instant.now())
            .durationSeconds(7200)
            .build());

    // Unwatched movie: no activity (should be excluded)
    createMovieWithFile("Unwatched Movie");

    // In-progress episode: has session progress
    inProgressEpisode = createEpisodeWithProgress("In Progress Episode", 900, 15.0, 3600);
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
    @DisplayName("Should return in-progress movies and episodes")
    void shouldReturnInProgressMoviesAndEpisodes() {
      var results = continueWatchingService.getContinueWatching(20);

      assertThat(results).hasSizeGreaterThanOrEqualTo(2);

      var ids = results.stream().map(item -> item.getId()).toList();
      assertThat(ids).contains(inProgressMovie.getId(), inProgressEpisode.getId());
    }

    @Test
    @DisplayName("Should exclude watched items from results")
    void shouldExcludeWatchedItemsFromResults() {
      var results = continueWatchingService.getContinueWatching(20);

      var titles =
          results.stream()
              .map(
                  item -> {
                    if (item instanceof Movie m) return m.getTitle();
                    if (item instanceof Episode e) return e.getTitle();
                    return null;
                  })
              .toList();

      assertThat(titles).isNotEmpty();
      assertThat(titles).doesNotContain("Watched Movie");
    }

    @Test
    @DisplayName("Should exclude unwatched items with no progress")
    void shouldExcludeUnwatchedItemsWithNoProgress() {
      var results = continueWatchingService.getContinueWatching(20);

      var titles =
          results.stream()
              .map(
                  item -> {
                    if (item instanceof Movie m) return m.getTitle();
                    if (item instanceof Episode e) return e.getTitle();
                    return null;
                  })
              .toList();

      assertThat(titles).isNotEmpty();
      assertThat(titles).doesNotContain("Unwatched Movie");
    }

    @Test
    @DisplayName("Should return empty list when limit is zero")
    void shouldReturnEmptyListWhenLimitIsZero() {
      var results = continueWatchingService.getContinueWatching(0);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Should respect limit parameter")
    void shouldRespectLimitParameter() {
      var results = continueWatchingService.getContinueWatching(1);

      assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("Should order by most recent activity descending")
    void shouldOrderByMostRecentActivityDescending() {
      var results = continueWatchingService.getContinueWatching(20);

      // The most recently created session progress should come first
      // inProgressEpisode was created after inProgressMovie in setup
      assertThat(results).hasSizeGreaterThanOrEqualTo(2);
      assertThat(results.getFirst().getId()).isEqualTo(inProgressEpisode.getId());
    }
  }
}
