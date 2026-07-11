package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
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
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.junit.jupiter.api.AfterAll;
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
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private SeriesService seriesService;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;

  private Library library;

  @BeforeAll
  void setup() {
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    // Watched Series: all episodes in watch_history
    var watched = createSeriesWithEpisodes("Watched Series", 2);
    for (var episodeId : watched.episodeIds()) {
      watchHistoryRepository.saveAndFlush(
          WatchHistory.builder()
              .profileId(profileId)
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
              .profileId(profileId)
              .collectableId(episodeId)
              .watchedAt(Instant.now())
              .durationSeconds(3600)
              .build());
    }
    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .profileId(profileId)
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
            .profileId(profileId)
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
            .profileId(profileId)
            .collectableId(partiallyWatched.episodeIds().getFirst())
            .watchedAt(Instant.now())
            .durationSeconds(3600)
            .build());

    // Unwatched Series: no watch activity at all
    createSeriesWithEpisodes("Unwatched Series", 2);
  }

  private record SeriesFixture(UUID seriesId, List<UUID> episodeIds, UUID firstEpisodeFileId) {}

  private record SeriesProgressFixture(UUID seriesId, UUID mediaFileId) {}

  private SeriesFixture createSeriesWithEpisodes(String title, int episodeCount) {
    return createSeriesWithEpisodes(library, title, episodeCount);
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
          MediaFilter.builder()
              .libraryId(library.getId())
              .profileId(profileId)
              .watchStatus(WatchStatus.WATCHED)
              .build();

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
              .profileId(profileId)
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
              .profileId(profileId)
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

  @Nested
  @DisplayName("Last Watched Sort")
  class LastWatchedSortTests {

    @Test
    @DisplayName("Should return series ordered by most recently watched DESC")
    void shouldReturnSeriesOrderedByMostRecentlyWatchedDesc() {
      var sortLibrary =
          libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      var baseline = Instant.parse("2026-01-01T00:00:00Z");
      var older = createSeriesWithSessionProgress(sortLibrary, "Older Series");
      var newer = createSeriesWithSessionProgress(sortLibrary, "Newer Series");
      createSeriesWithEpisodes(sortLibrary, "No Progress Series", 1);
      pinSessionProgressTimestamp(older.mediaFileId(), baseline);
      pinSessionProgressTimestamp(newer.mediaFileId(), baseline.plusSeconds(1));

      var filter =
          MediaFilter.builder()
              .libraryId(sortLibrary.getId())
              .profileId(profileId)
              .sortBy(OrderMediaBy.LAST_WATCHED)
              .sortDirection(SortOrder.DESC)
              .build();

      var page = seriesService.getSeriesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactly("Newer Series", "Older Series", "No Progress Series");
    }

    @Test
    @DisplayName("Should paginate forward using cursor when sorted by LAST_WATCHED DESC")
    void shouldPaginateForwardUsingCursorWhenSortedByLastWatchedDesc() {
      var paginationLibrary =
          libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      var baseline = Instant.parse("2026-01-01T00:00:00Z");
      var first = createSeriesWithSessionProgress(paginationLibrary, "First");
      var second = createSeriesWithSessionProgress(paginationLibrary, "Second");
      var third = createSeriesWithSessionProgress(paginationLibrary, "Third");
      pinSessionProgressTimestamp(first.mediaFileId(), baseline);
      pinSessionProgressTimestamp(second.mediaFileId(), baseline.plusSeconds(1));
      pinSessionProgressTimestamp(third.mediaFileId(), baseline.plusSeconds(2));

      var filter =
          MediaFilter.builder()
              .libraryId(paginationLibrary.getId())
              .profileId(profileId)
              .sortBy(OrderMediaBy.LAST_WATCHED)
              .sortDirection(SortOrder.DESC)
              .build();

      var page1 = seriesService.getSeriesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items()).hasSize(1);
      assertThat(page1.items().getFirst().item().getId()).isEqualTo(third.seriesId());
      assertThat(page1.hasNextPage()).isTrue();

      var page2 =
          seriesService.getSeriesWithFilter(
              buildForwardContinuation(1, filter, page1.items().getLast()));
      assertThat(page2.items()).hasSize(1);
      assertThat(page2.items().getFirst().item().getId()).isEqualTo(second.seriesId());
      assertThat(page2.hasNextPage()).isTrue();

      var page3 =
          seriesService.getSeriesWithFilter(
              buildForwardContinuation(1, filter, page2.items().getLast()));
      assertThat(page3.items()).hasSize(1);
      assertThat(page3.items().getFirst().item().getId()).isEqualTo(first.seriesId());
      assertThat(page3.hasNextPage()).isFalse();

      var allIds =
          Stream.of(page1, page2, page3)
              .flatMap(p -> p.items().stream())
              .map(pi -> pi.item().getId())
              .toList();
      assertThat(allIds).doesNotHaveDuplicates();
    }

    private void pinSessionProgressTimestamp(UUID mediaFileId, Instant timestamp) {
      dsl.update(Tables.SESSION_PROGRESS)
          .set(Tables.SESSION_PROGRESS.LAST_MODIFIED_ON, timestamp.atOffset(ZoneOffset.UTC))
          .where(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(mediaFileId))
          .execute();
    }

    private SeriesProgressFixture createSeriesWithSessionProgress(Library lib, String title) {
      var fixture = createSeriesWithEpisodes(lib, title, 1);

      sessionProgressRepository.saveAndFlush(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .profileId(profileId)
              .mediaFileId(fixture.firstEpisodeFileId())
              .positionSeconds(600)
              .percentComplete(25.0)
              .durationSeconds(2400)
              .build());

      return new SeriesProgressFixture(fixture.seriesId(), fixture.firstEpisodeFileId());
    }
  }

  private SeriesFixture createSeriesWithEpisodes(Library lib, String title, int episodeCount) {
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title(title).titleSort(title).library(lib).build());

    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(lib).build());

    var episodeIds = new java.util.ArrayList<UUID>();
    UUID firstFileId = null;

    for (int i = 1; i <= episodeCount; i++) {
      var file =
          MediaFile.builder()
              .libraryId(lib.getId())
              .status(MediaFileStatus.MATCHED)
              .filename(title.toLowerCase().replace(' ', '-') + "-s01e0" + i + ".mkv")
              .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
              .build();

      var episode =
          episodeRepository.saveAndFlush(
              Episode.builder()
                  .episodeNumber(i)
                  .season(season)
                  .library(lib)
                  .files(Set.of(file))
                  .build());

      episodeIds.add(episode.getId());
      if (firstFileId == null) {
        firstFileId = episode.getFiles().iterator().next().getId();
      }
    }

    return new SeriesFixture(series.getId(), episodeIds, firstFileId);
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }
}
