package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
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
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the watchStatus field, the watch status filter, Continue Watching and watchProgress to one
 * rule: progress counts only when it is newer than the latest completion that has not been
 * dismissed. Continue Watching also follows the lastWatchedAt field's order.
 */
@Tag("IntegrationTest")
@DisplayName("Watch State Parity Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchStateParityIT extends AbstractIntegrationTest {

  private static final Instant BASELINE = Instant.parse("2026-03-01T00:00:00Z");
  private static final int CONTINUE_WATCHING_LIMIT = 500;

  @Autowired private WatchStatusService watchStatusService;
  @Autowired private ContinueWatchingService continueWatchingService;
  @Autowired private MovieService movieService;
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

  @Builder
  record Activity(
      String name,
      @Singular("progress") List<ProgressAt> progress,
      @Singular List<CompletionAt> completions) {

    @Override
    public String toString() {
      return name;
    }
  }

  record ProgressAt(int positionSeconds, int minute) {}

  record CompletionAt(int minute, boolean dismissed) {}

  record MovieCase(Activity activity, WatchStatus expectedStatus) {}

  static Stream<MovieCase> movieCases() {
    return Stream.of(
        new MovieCase(Activity.builder().name("no activity").build(), WatchStatus.UNWATCHED),
        new MovieCase(
            Activity.builder().name("progress only").progress(new ProgressAt(600, 1)).build(),
            WatchStatus.IN_PROGRESS),
        new MovieCase(
            Activity.builder().name("progress at zero only").progress(new ProgressAt(0, 1)).build(),
            WatchStatus.UNWATCHED),
        new MovieCase(
            Activity.builder()
                .name("completion only")
                .completion(new CompletionAt(1, false))
                .build(),
            WatchStatus.WATCHED),
        new MovieCase(
            Activity.builder()
                .name("completion newer than progress")
                .progress(new ProgressAt(600, 1))
                .completion(new CompletionAt(2, false))
                .build(),
            WatchStatus.WATCHED),
        new MovieCase(
            Activity.builder()
                .name("progress newer than completion")
                .completion(new CompletionAt(1, false))
                .progress(new ProgressAt(600, 2))
                .build(),
            WatchStatus.IN_PROGRESS),
        new MovieCase(
            Activity.builder()
                .name("progress at zero newer than completion")
                .completion(new CompletionAt(1, false))
                .progress(new ProgressAt(0, 2))
                .build(),
            WatchStatus.WATCHED),
        new MovieCase(
            Activity.builder()
                .name("dismissed completion newer than progress")
                .progress(new ProgressAt(600, 1))
                .completion(new CompletionAt(2, true))
                .build(),
            WatchStatus.IN_PROGRESS),
        new MovieCase(
            Activity.builder()
                .name("newest session at zero after a session with a position")
                .progress(new ProgressAt(600, 1))
                .progress(new ProgressAt(0, 2))
                .build(),
            WatchStatus.IN_PROGRESS),
        new MovieCase(
            Activity.builder()
                .name("one session newer and one older than the completion")
                .progress(new ProgressAt(600, 1))
                .completion(new CompletionAt(2, false))
                .progress(new ProgressAt(900, 3))
                .build(),
            WatchStatus.IN_PROGRESS));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("movieCases")
  @DisplayName("Should agree on the movie's watch state across field, filter and Continue Watching")
  void shouldAgreeOnMovieWatchStateAcrossFieldFilterAndContinueWatching(MovieCase movieCase) {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie = createMovieWithFile(library);
    applyActivity(movie, movieCase.activity());

    var status =
        watchStatusService
            .getWatchStatusForMoviesAndEpisodes(profileId, List.of(movie.getId()))
            .get(movie.getId());
    var progress =
        watchStatusService.getProgressForMoviesAndEpisodes(profileId, List.of(movie.getId()));

    assertThat(status).isEqualTo(movieCase.expectedStatus());
    for (var candidate : WatchStatus.values()) {
      assertThat(movieIdsFilteredBy(library, candidate).contains(movie.getId()))
          .as("watch status filter %s", candidate)
          .isEqualTo(candidate == status);
    }

    assertThat(continueWatchingIds().contains(movie.getId()))
        .as("Continue Watching")
        .isEqualTo(status == WatchStatus.IN_PROGRESS);
    assertThat(progress.containsKey(movie.getId()))
        .as("watchProgress")
        .isEqualTo(status == WatchStatus.IN_PROGRESS);
  }

  record SeriesCase(String name, List<Activity> episodeActivities, WatchStatus expectedStatus) {

    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<SeriesCase> seriesCases() {
    var watched = Activity.builder().name("watched").completion(new CompletionAt(2, false)).build();
    var staleProgressBeforeCompletion =
        Activity.builder()
            .name("stale progress")
            .progress(new ProgressAt(600, 1))
            .completion(new CompletionAt(2, false))
            .build();
    var rewatch =
        Activity.builder()
            .name("rewatch")
            .completion(new CompletionAt(1, false))
            .progress(new ProgressAt(600, 2))
            .build();
    var none = Activity.builder().name("none").build();
    var zeroOnly = Activity.builder().name("zero").progress(new ProgressAt(0, 1)).build();

    return Stream.of(
        new SeriesCase("every episode watched", List.of(watched, watched), WatchStatus.WATCHED),
        new SeriesCase(
            "stale progress before every completion",
            List.of(staleProgressBeforeCompletion, watched),
            WatchStatus.WATCHED),
        new SeriesCase("one episode rewatched", List.of(rewatch, watched), WatchStatus.IN_PROGRESS),
        new SeriesCase("one episode watched", List.of(watched, none), WatchStatus.IN_PROGRESS),
        new SeriesCase("only progress at zero", List.of(zeroOnly, none), WatchStatus.UNWATCHED),
        new SeriesCase("no activity", List.of(none, none), WatchStatus.UNWATCHED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("seriesCases")
  @DisplayName("Should agree on the series watch state across field and filter")
  void shouldAgreeOnSeriesWatchStateAcrossFieldAndFilter(SeriesCase seriesCase) {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Parity Series")
                .titleSort("Parity Series")
                .library(library)
                .build());
    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(library).build());
    var episodeNumber = 1;
    for (var activity : seriesCase.episodeActivities()) {
      applyActivity(createEpisodeWithFile(season, episodeNumber++), activity);
    }

    var status =
        watchStatusService
            .getWatchStatusForSeries(profileId, List.of(series.getId()))
            .get(series.getId());

    assertThat(status).isEqualTo(seriesCase.expectedStatus());
    for (var candidate : WatchStatus.values()) {
      assertThat(seriesIdsFilteredBy(library, candidate).contains(series.getId()))
          .as("watch status filter %s", candidate)
          .isEqualTo(candidate == status);
    }
  }

  @Test
  @DisplayName("Should order Continue Watching by the lastWatchedAt field, newest first")
  void shouldOrderContinueWatchingByTheLastWatchedAtFieldNewestFirst() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var created =
        Stream.of(
                Activity.builder().name("early").progress(new ProgressAt(600, 11)).build(),
                Activity.builder().name("late").progress(new ProgressAt(600, 13)).build(),
                Activity.builder()
                    .name("rewatch")
                    .completion(new CompletionAt(10, false))
                    .progress(new ProgressAt(600, 12))
                    .build())
            .map(
                activity -> {
                  var movie = createMovieWithFile(library);
                  applyActivity(movie, activity);
                  return movie.getId();
                })
            .toList();

    var ids = continueWatchingIds();
    var lastWatchedAt =
        watchStatusService.getLastWatchedAtForItems(
            profileId, CollectableScope.MOVIE_OR_EPISODE, ids);
    // PostgreSQL orders uuid values byte by byte, which matches their text order.
    var expected =
        ids.stream()
            .sorted(
                Comparator.<UUID, Instant>comparing(lastWatchedAt::get)
                    .thenComparing(UUID::toString)
                    .reversed())
            .toList();

    assertThat(ids).containsAll(created).containsExactlyElementsOf(expected);
  }

  private void applyActivity(BaseCollectable<?> item, Activity activity) {
    var mediaFileId = item.getFiles().iterator().next().getId();
    activity.progress().forEach(progressAt -> saveProgress(mediaFileId, progressAt));
    activity.completions().forEach(completionAt -> saveCompletion(item.getId(), completionAt));
  }

  private void saveProgress(UUID mediaFileId, ProgressAt progressAt) {
    var sessionId = UUID.randomUUID();
    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(sessionId)
            .profileId(profileId)
            .mediaFileId(mediaFileId)
            .positionSeconds(progressAt.positionSeconds())
            .percentComplete(25.0)
            .durationSeconds(7200)
            .build());
    auditColumns.overwriteLastModifiedOn(
        SESSION_PROGRESS, SESSION_PROGRESS.SESSION_ID.eq(sessionId), minute(progressAt.minute()));
  }

  private void saveCompletion(UUID itemId, CompletionAt completionAt) {
    var watchedAt = minute(completionAt.minute());
    watchHistoryRepository.saveAndFlush(
        WatchHistory.builder()
            .profileId(profileId)
            .collectableId(itemId)
            .watchedAt(watchedAt)
            .dismissedAt(completionAt.dismissed() ? watchedAt.plusSeconds(1) : null)
            .durationSeconds(7200)
            .build());
  }

  private static Instant minute(int minute) {
    return BASELINE.plusSeconds(60L * minute);
  }

  private List<UUID> movieIdsFilteredBy(Library library, WatchStatus status) {
    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .profileId(profileId)
            .watchStatus(status)
            .build();
    return movieService.getMoviesWithFilter(buildForwardOptions(20, filter)).items().stream()
        .map(item -> item.item().getId())
        .toList();
  }

  private List<UUID> seriesIdsFilteredBy(Library library, WatchStatus status) {
    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .profileId(profileId)
            .watchStatus(status)
            .build();
    return seriesService.getSeriesWithFilter(buildForwardOptions(20, filter)).items().stream()
        .map(item -> item.item().getId())
        .toList();
  }

  private List<UUID> continueWatchingIds() {
    return continueWatchingService
        .getPage(profileId, firstRows(CONTINUE_WATCHING_LIMIT))
        .rows()
        .stream()
        .map(item -> item.item().getId())
        .toList();
  }

  private Movie createMovieWithFile(Library library) {
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Parity Movie")
            .titleSort("Parity Movie")
            .files(Set.of(buildFile(library)))
            .library(library)
            .build());
  }

  private Episode createEpisodeWithFile(Season season, int episodeNumber) {
    return episodeRepository.saveAndFlush(
        Episode.builder()
            .episodeNumber(episodeNumber)
            .season(season)
            .library(season.getLibrary())
            .files(Set.of(buildFile(season.getLibrary())))
            .build());
  }

  private static MediaFile buildFile(Library library) {
    return MediaFile.builder()
        .libraryId(library.getId())
        .status(MediaFileStatus.MATCHED)
        .filename("parity-" + UUID.randomUUID() + ".mkv")
        .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
        .build();
  }
}
