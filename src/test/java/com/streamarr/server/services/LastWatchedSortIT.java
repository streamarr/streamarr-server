package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
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
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Last Watched Sort Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LastWatchedSortIT extends AbstractIntegrationTest {

  private static final Instant BASELINE = Instant.parse("2026-04-01T00:00:00Z");

  @Autowired private MovieService movieService;
  @Autowired private WatchStatusService watchStatusService;
  @Autowired private SeriesService seriesService;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport authTestSupport;

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;

  @BeforeAll
  void setup() {
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should order a just-finished movie first when sorted by last watched descending")
  void shouldOrderJustFinishedMovieFirstWhenSortedByLastWatchedDescending() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var paused = createMovie(library, "Paused");
    var finished = createMovie(library, "Finished");
    saveProgress(paused, 600, minute(1));
    saveCompletion(finished, minute(2));

    assertThat(movieIdsByLastWatched(library)).containsExactly(finished.getId(), paused.getId());
  }

  @Test
  @DisplayName("Should order each movie by the later of its progress and its completion")
  void shouldOrderEachMovieByTheLaterOfItsProgressAndItsCompletion() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var rewatched = createMovie(library, "Rewatched");
    var finishedAfterPause = createMovie(library, "Finished After Pause");
    var paused = createMovie(library, "Paused");
    saveCompletion(rewatched, minute(1));
    saveProgress(rewatched, 600, minute(4));
    saveProgress(finishedAfterPause, 600, minute(2));
    saveCompletion(finishedAfterPause, minute(3));
    saveProgress(paused, 600, minute(2));

    assertThat(movieIdsByLastWatched(library))
        .containsExactly(rewatched.getId(), finishedAfterPause.getId(), paused.getId());
  }

  @Test
  @DisplayName("Should order a movie by its progress when its completion is dismissed")
  void shouldOrderMovieByItsProgressWhenItsCompletionIsDismissed() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var dismissed = createMovie(library, "Dismissed");
    var paused = createMovie(library, "Paused");
    saveProgress(dismissed, 600, minute(1));
    saveDismissedCompletion(dismissed, minute(5));
    saveProgress(paused, 600, minute(2));

    assertThat(movieIdsByLastWatched(library)).containsExactly(paused.getId(), dismissed.getId());
  }

  @Test
  @DisplayName("Should order a movie last when its only session has no position")
  void shouldOrderMovieLastWhenItsOnlySessionHasNoPosition() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var opened = createMovie(library, "Opened");
    var paused = createMovie(library, "Paused");
    saveProgress(opened, 0, minute(5));
    saveProgress(paused, 600, minute(1));

    assertThat(movieIdsByLastWatched(library)).containsExactly(paused.getId(), opened.getId());
  }

  @Test
  @DisplayName("Should page across the last-watched cursor when completions set the order")
  void shouldPageAcrossTheLastWatchedCursorWhenCompletionsSetTheOrder() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var first = createMovie(library, "First");
    var second = createMovie(library, "Second");
    var third = createMovie(library, "Third");
    saveCompletion(first, minute(1));
    saveProgress(second, 600, minute(1));
    saveCompletion(second, minute(2));
    saveCompletion(third, minute(3));
    var filter = lastWatchedFilter(library);

    var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
    var page2 =
        movieService.getMoviesWithFilter(
            buildForwardContinuation(1, filter, page1.items().getLast()));
    var page3 =
        movieService.getMoviesWithFilter(
            buildForwardContinuation(1, filter, page2.items().getLast()));

    assertThat(idsOf(page1)).containsExactly(third.getId());
    assertThat(idsOf(page2)).containsExactly(second.getId());
    assertThat(idsOf(page3)).containsExactly(first.getId());
    assertThat(page3.hasNextPage()).isFalse();
  }

  @Test
  @DisplayName("Should order a series by its latest episode completion when sorted by last watched")
  void shouldOrderSeriesByItsLatestEpisodeCompletionWhenSortedByLastWatched() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var finished = createEpisodeOfNewSeries(library, "Finished");
    var paused = createEpisodeOfNewSeries(library, "Paused");
    saveProgress(finished, 600, minute(1));
    saveCompletion(finished, minute(3));
    saveProgress(paused, 600, minute(2));

    var page =
        seriesService.getSeriesWithFilter(buildForwardOptions(20, lastWatchedFilter(library)));

    assertThat(page.items())
        .extracting(item -> item.item().getTitle())
        .containsExactly("Finished", "Paused");
  }

  @Test
  @DisplayName("Should give each movie one last-watched time in the sort cursor and in the field")
  void shouldGiveEachMovieOneLastWatchedTimeInTheSortCursorAndInTheField() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var rewatched = createMovie(library, "Rewatched");
    var finished = createMovie(library, "Finished");
    var unwatched = createMovie(library, "Unwatched");
    saveCompletion(rewatched, minute(1));
    saveProgress(rewatched, 600, minute(3));
    saveProgress(finished, 600, minute(1));
    saveCompletion(finished, minute(2));

    var page =
        movieService.getMoviesWithFilter(buildForwardOptions(20, lastWatchedFilter(library)));
    var fieldValues =
        watchStatusService.getLastWatchedAtForItems(
            profileId,
            CollectableScope.MOVIE_OR_EPISODE,
            List.of(rewatched.getId(), finished.getId(), unwatched.getId()));

    assertThat(page.items())
        .hasSize(3)
        .allSatisfy(
            item -> assertThat(item.sortValue()).isEqualTo(fieldValues.get(item.item().getId())));
    assertThat(fieldValues)
        .containsOnly(entry(rewatched.getId(), minute(3)), entry(finished.getId(), minute(2)));
  }

  @Test
  @DisplayName("Should give each series one last-watched time in the sort cursor and in the field")
  void shouldGiveEachSeriesOneLastWatchedTimeInTheSortCursorAndInTheField() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var finished = createEpisodeOfNewSeries(library, "Finished");
    var paused = createEpisodeOfNewSeries(library, "Paused");
    saveCompletion(finished, minute(4));
    saveProgress(paused, 600, minute(2));

    var page =
        seriesService.getSeriesWithFilter(buildForwardOptions(20, lastWatchedFilter(library)));
    var seriesIds = page.items().stream().map(item -> item.item().getId()).toList();
    var fieldValues =
        watchStatusService.getLastWatchedAtForItems(profileId, CollectableScope.SERIES, seriesIds);

    assertThat(page.items())
        .allSatisfy(
            item -> assertThat(item.sortValue()).isEqualTo(fieldValues.get(item.item().getId())));
    assertThat(fieldValues.values()).containsExactlyInAnyOrder(minute(4), minute(2));
  }

  @Test
  @DisplayName("Should give a season the latest last-watched time among its episodes")
  void shouldGiveASeasonTheLatestLastWatchedTimeAmongItsEpisodes() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var episode = createEpisodeOfNewSeries(library, "Season Series");
    saveProgress(episode, 600, minute(2));
    saveCompletion(episode, minute(5));
    var seasonId = episode.getSeason().getId();

    var fieldValues =
        watchStatusService.getLastWatchedAtForItems(
            profileId, CollectableScope.SEASON, List.of(seasonId));

    assertThat(fieldValues).containsOnly(entry(seasonId, minute(5)));
  }

  private List<UUID> movieIdsByLastWatched(Library library) {
    return idsOf(
        movieService.getMoviesWithFilter(buildForwardOptions(20, lastWatchedFilter(library))));
  }

  private MediaFilter lastWatchedFilter(Library library) {
    return MediaFilter.builder()
        .libraryId(library.getId())
        .profileId(profileId)
        .sortBy(OrderMediaBy.LAST_WATCHED)
        .sortDirection(SortOrder.DESC)
        .build();
  }

  private static <T extends BaseCollectable<?>> List<UUID> idsOf(MediaPage<T> page) {
    return page.items().stream().map(item -> item.item().getId()).toList();
  }

  private void saveProgress(BaseCollectable<?> item, int positionSeconds, Instant lastModifiedOn) {
    var sessionId = UUID.randomUUID();
    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(sessionId)
            .profileId(profileId)
            .mediaFileId(item.getFiles().iterator().next().getId())
            .positionSeconds(positionSeconds)
            .percentComplete(25.0)
            .durationSeconds(2400)
            .build());
    auditColumns.overwriteLastModifiedOn(
        SESSION_PROGRESS, SESSION_PROGRESS.SESSION_ID.eq(sessionId), lastModifiedOn);
  }

  private void saveCompletion(BaseCollectable<?> item, Instant watchedAt) {
    watchHistoryRepository.saveAndFlush(completionBuilder(item, watchedAt).build());
  }

  private void saveDismissedCompletion(BaseCollectable<?> item, Instant watchedAt) {
    watchHistoryRepository.saveAndFlush(
        completionBuilder(item, watchedAt).dismissedAt(watchedAt.plusSeconds(1)).build());
  }

  private WatchHistory.WatchHistoryBuilder<?, ?> completionBuilder(
      BaseCollectable<?> item, Instant watchedAt) {
    return WatchHistory.builder()
        .profileId(profileId)
        .collectableId(item.getId())
        .watchedAt(watchedAt)
        .durationSeconds(2400);
  }

  private static Instant minute(int minute) {
    return BASELINE.plusSeconds(60L * minute);
  }

  private Movie createMovie(Library library, String title) {
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title(title)
            .titleSort(title)
            .files(Set.of(buildFile(library)))
            .library(library)
            .build());
  }

  private Episode createEpisodeOfNewSeries(Library library, String seriesTitle) {
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title(seriesTitle).titleSort(seriesTitle).library(library).build());
    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(library).build());
    return episodeRepository.saveAndFlush(
        Episode.builder()
            .episodeNumber(1)
            .season(season)
            .library(library)
            .files(Set.of(buildFile(library)))
            .build());
  }

  private static MediaFile buildFile(Library library) {
    return MediaFile.builder()
        .libraryId(library.getId())
        .status(MediaFileStatus.MATCHED)
        .filename("last-watched-" + UUID.randomUUID() + ".mkv")
        .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
        .build();
  }
}
