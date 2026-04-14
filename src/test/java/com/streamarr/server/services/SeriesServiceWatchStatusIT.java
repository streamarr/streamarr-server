package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import java.time.Instant;
import java.util.List;
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
@DisplayName("Series Service Watch Status Filter Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeriesServiceWatchStatusIT extends AbstractIntegrationTest {

  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private SeriesService seriesService;

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Library library;

  @BeforeAll
  void setup() {
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    // Watched Series: all episodes in watch_history
    var watched = createSeriesWithEpisodes("Watched Series", 2);
    for (var episodeId : watched.episodeIds()) {
      watchHistoryRepository.saveAndFlush(
          WatchHistory.builder()
              .userId(USER_ID)
              .collectableId(episodeId)
              .watchedAt(Instant.now())
              .durationSeconds(3600)
              .build());
    }

    // Watched-With-Residual-Progress Series: all episodes watched AND one has session_progress.
    // Exercises the WATCHED rule precedence over IN_PROGRESS.
    var watchedWithProgress = createSeriesWithEpisodes("Watched With Residual Progress", 2);
    for (var episodeId : watchedWithProgress.episodeIds()) {
      watchHistoryRepository.saveAndFlush(
          WatchHistory.builder()
              .userId(USER_ID)
              .collectableId(episodeId)
              .watchedAt(Instant.now())
              .durationSeconds(3600)
              .build());
    }
    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .userId(USER_ID)
            .mediaFileId(watchedWithProgress.firstEpisodeFileId())
            .positionSeconds(60)
            .percentComplete(2.0)
            .durationSeconds(3600)
            .build());

    // In-Progress Series (via session_progress): one episode has progress, none in watch_history
    var sessionInProgress = createSeriesWithEpisodes("Session In Progress Series", 2);
    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .userId(USER_ID)
            .mediaFileId(sessionInProgress.firstEpisodeFileId())
            .positionSeconds(900)
            .percentComplete(25.0)
            .durationSeconds(3600)
            .build());

    // Partially-Watched Series: one episode watched via watch_history, one untouched.
    // The critical boundary: partial watch_history must NOT count as WATCHED.
    var partiallyWatched = createSeriesWithEpisodes("Partially Watched Series", 2);
    watchHistoryRepository.saveAndFlush(
        WatchHistory.builder()
            .userId(USER_ID)
            .collectableId(partiallyWatched.episodeIds().getFirst())
            .watchedAt(Instant.now())
            .durationSeconds(3600)
            .build());

    // Unwatched Series: no watch activity at all
    createSeriesWithEpisodes("Unwatched Series", 2);
  }

  private record SeriesFixture(List<UUID> episodeIds, UUID firstEpisodeFileId) {}

  private SeriesFixture createSeriesWithEpisodes(String title, int episodeCount) {
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title(title).titleSort(title).library(library).build());

    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(library).build());

    var episodeIds = new java.util.ArrayList<UUID>();
    UUID firstFileId = null;

    for (int i = 1; i <= episodeCount; i++) {
      var file =
          MediaFile.builder()
              .libraryId(library.getId())
              .status(MediaFileStatus.MATCHED)
              .filename(title.toLowerCase().replace(' ', '-') + "-s01e0" + i + ".mkv")
              .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
              .build();

      var episode =
          episodeRepository.saveAndFlush(
              Episode.builder()
                  .episodeNumber(i)
                  .season(season)
                  .library(library)
                  .files(Set.of(file))
                  .build());

      episodeIds.add(episode.getId());
      if (firstFileId == null) {
        firstFileId = episode.getFiles().iterator().next().getId();
      }
    }

    return new SeriesFixture(episodeIds, firstFileId);
  }

  @Nested
  @DisplayName("Watch Status Filter")
  class WatchStatusFilterTests {

    @Test
    @DisplayName(
        "Should return only fully-watched series and exclude partially-watched"
            + " when filtering by WATCHED status")
    void
        shouldReturnOnlyFullyWatchedSeriesAndExcludePartiallyWatchedWhenFilteringByWatchedStatus() {
      var filter =
          MediaFilter.builder().libraryId(library.getId()).watchStatus(WatchStatus.WATCHED).build();

      var page = seriesService.getSeriesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactlyInAnyOrder("Watched Series", "Watched With Residual Progress");
    }

    @Test
    @DisplayName(
        "Should return partially-watched and session-in-progress series"
            + " when filtering by IN_PROGRESS status")
    void shouldReturnPartiallyWatchedAndSessionInProgressSeriesWhenFilteringByInProgressStatus() {
      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .watchStatus(WatchStatus.IN_PROGRESS)
              .build();

      var page = seriesService.getSeriesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactlyInAnyOrder("Session In Progress Series", "Partially Watched Series");
    }

    @Test
    @DisplayName("Should return only series with no watch activity when filtering by UNWATCHED")
    void shouldReturnOnlySeriesWithNoWatchActivityWhenFilteringByUnwatched() {
      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .watchStatus(WatchStatus.UNWATCHED)
              .build();

      var page = seriesService.getSeriesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactly("Unwatched Series");
    }

    @Test
    @DisplayName("Should return all seeded series when no watch status filter is applied")
    void shouldReturnAllSeededSeriesWhenNoWatchStatusFilterIsApplied() {
      var filter = MediaFilter.builder().libraryId(library.getId()).build();

      var page = seriesService.getSeriesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactlyInAnyOrder(
              "Watched Series",
              "Watched With Residual Progress",
              "Session In Progress Series",
              "Partially Watched Series",
              "Unwatched Series");
    }
  }
}
