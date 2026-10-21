package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
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
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

@Tag("IntegrationTest")
@DisplayName("Continue Watching Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContinueWatchingServiceIT extends AbstractIntegrationTest {

  @Autowired private ContinueWatchingService continueWatchingService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private AuditColumnFixtures auditColumns;

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;
  private static final Instant MOVIE_ACTIVITY_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EPISODE_ACTIVITY_AT = Instant.parse("2026-02-01T00:00:00Z");
  private static final int TIED_TITLE_COUNT = 6;

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
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
    movieLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    seriesLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    // In-progress movie: has session progress
    inProgressMovie = createMovieWithProgress("In Progress Movie", 1800, 25.0, 7200);
    inProgressMovieFileId = inProgressMovie.getFiles().iterator().next().getId();

    // Watched movie: has watch_history (should be excluded)
    watchedMovie = createMovieWithFile("Watched Movie");
    watchHistoryRepository.saveAndFlush(
        WatchHistory.builder()
            .profileId(profileId)
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
    auditColumns.overwriteLastModifiedOn(
        SESSION_PROGRESS, SESSION_PROGRESS.MEDIA_FILE_ID.eq(mediaFileId), timestamp);
  }

  private Movie createMovieWithFile(String title) {
    var file =
        MediaFile.builder()
            .libraryId(movieLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(title.toLowerCase().replace(' ', '-') + ".mkv")
            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
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
            .profileId(profileId)
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
            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
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
            .profileId(profileId)
            .mediaFileId(fileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .build());

    return episode;
  }

  private MediaPage<BaseCollectable<?>> page(UUID owner, int limit) {
    return continueWatchingService.getPage(
        owner, buildForwardOptions(limit, MediaFilter.builder().build()));
  }

  private List<BaseCollectable<?>> items(UUID owner, int limit) {
    return page(owner, limit).items().stream().map(PageItem::item).toList();
  }

  @Nested
  @DisplayName("Continue Watching membership and ordering")
  class GetContinueWatchingTests {

    @Test
    @DisplayName("Should return matching page items for a batch beyond the shelf cap")
    void shouldReturnMatchingPageItemsForBatchBeyondShelfCap() {
      var missing = UUID.randomUUID();
      var items =
          continueWatchingService.findMatchingItems(
              profileId,
              Set.of(
                  inProgressMovie.getId(),
                  inProgressEpisode.getId(),
                  watchedMovie.getId(),
                  unwatchedMovie.getId(),
                  missing));
      var page = page(profileId, 1);

      assertThat(items).containsOnlyKeys(inProgressMovie.getId(), inProgressEpisode.getId());
      assertThat(items.get(inProgressMovie.getId()).item().getId())
          .isEqualTo(inProgressMovie.getId());
      assertThat(items.get(inProgressMovie.getId()).sortValue()).isEqualTo(MOVIE_ACTIVITY_AT);
      assertThat(items.get(inProgressEpisode.getId()).sortValue()).isEqualTo(EPISODE_ACTIVITY_AT);
      assertThat(page.items()).containsExactly(items.get(inProgressEpisode.getId()));
      assertThat(page.hasNextPage()).isTrue();
      assertThat(continueWatchingService.findMatchingItems(profileId, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("Should agree on placement when completion and resume position membership changes")
    void shouldAgreeOnPlacementWhenCompletionAndResumePositionMembershipChanges() {
      var owner = authTestSupport.createIdentity();
      var ownerId = owner.profile().getId();
      var movie = createMovieWithFile("Membership transitions");
      var fileId = movie.getFiles().iterator().next().getId();
      try {
        var progress = saveProgress(ownerId, fileId);
        pinSessionProgressTimestamp(fileId, MOVIE_ACTIVITY_AT);
        assertPlaced(ownerId, movie.getId());
        var completion =
            watchHistoryRepository.saveAndFlush(
                WatchHistory.builder()
                    .profileId(ownerId)
                    .collectableId(movie.getId())
                    .watchedAt(MOVIE_ACTIVITY_AT)
                    .build());
        assertNotPlaced(ownerId, movie.getId());

        pinSessionProgressTimestamp(fileId, MOVIE_ACTIVITY_AT.plusNanos(1000));
        assertPlaced(ownerId, movie.getId());
        pinSessionProgressTimestamp(fileId, MOVIE_ACTIVITY_AT);
        completion.setDismissedAt(Instant.now());
        watchHistoryRepository.saveAndFlush(completion);
        assertPlaced(ownerId, movie.getId());

        progress.setPositionSeconds(0);
        sessionProgressRepository.saveAndFlush(progress);
        assertNotPlaced(ownerId, movie.getId());
      } finally {
        authTestSupport.deleteIdentity(owner);
      }
    }

    private void assertPlaced(UUID owner, UUID itemId) {
      assertThat(items(owner, 12)).extracting(BaseCollectable::getId).containsExactly(itemId);
      var matching = continueWatchingService.findMatchingItems(owner, Set.of(itemId));
      assertThat(matching).containsOnlyKeys(itemId);
      assertThat(matching.get(itemId).sortValue()).isInstanceOf(Instant.class);
    }

    private void assertNotPlaced(UUID owner, UUID itemId) {
      assertThat(items(owner, 12)).isEmpty();
      assertThat(continueWatchingService.findMatchingItems(owner, Set.of(itemId))).isEmpty();
    }

    @Test
    @DisplayName("Should return exactly the in-progress movie and episode when querying")
    void shouldReturnExactlyTheInProgressMovieAndEpisodeWhenQuerying() {
      var results = items(profileId, 20);

      assertThat(results)
          .extracting(BaseCollectable::getId)
          .containsExactlyInAnyOrder(inProgressMovie.getId(), inProgressEpisode.getId());
    }

    @Test
    @DisplayName("Should not include another profiles progress when querying")
    void shouldNotIncludeAnotherProfilesProgressWhenQuerying() {
      var otherIdentity = authTestSupport.createIdentity();
      var otherProfileId = otherIdentity.profile().getId();
      var otherMovie = createMovieWithFile("Other Profile Movie");
      var otherFileId = otherMovie.getFiles().iterator().next().getId();
      try {
        sessionProgressRepository.saveAndFlush(
            SessionProgress.builder()
                .sessionId(UUID.randomUUID())
                .profileId(otherProfileId)
                .mediaFileId(otherFileId)
                .positionSeconds(600)
                .percentComplete(20.0)
                .durationSeconds(3000)
                .build());

        // Profile isolation here is a raw jOOQ predicate, not an active guard: this pin
        // fails if the profile filter is ever dropped from the continue-watching query. The
        // non-emptiness guard keeps doesNotContain from passing vacuously on a broken query.
        var results = items(profileId, 20);
        assertThat(results)
            .isNotEmpty()
            .extracting(BaseCollectable::getId)
            .doesNotContain(otherMovie.getId());

        var otherResults = items(otherProfileId, 20);
        assertThat(otherResults)
            .extracting(BaseCollectable::getId)
            .containsExactly(otherMovie.getId());
        var requested = Set.of(inProgressMovie.getId(), otherMovie.getId());
        var ownPositions = continueWatchingService.findMatchingItems(profileId, requested);
        var otherPositions = continueWatchingService.findMatchingItems(otherProfileId, requested);
        assertThat(ownPositions).containsKey(inProgressMovie.getId());
        assertThat(ownPositions).doesNotContainKey(otherMovie.getId());
        assertThat(otherPositions).containsKey(otherMovie.getId());
        assertThat(otherPositions).doesNotContainKey(inProgressMovie.getId());
      } finally {
        authTestSupport.deleteIdentity(otherIdentity);
      }
    }

    @Test
    @DisplayName("Should not include the watched movie when querying")
    void shouldNotIncludeTheWatchedMovieWhenQuerying() {
      var results = items(profileId, 20);

      assertThat(results)
          .isNotEmpty()
          .extracting(BaseCollectable::getId)
          .doesNotContain(watchedMovie.getId());
    }

    @Test
    @DisplayName("Should not include the unwatched movie with no session progress when querying")
    void shouldNotIncludeTheUnwatchedMovieWithNoSessionProgressWhenQuerying() {
      var results = items(profileId, 20);

      assertThat(results)
          .isNotEmpty()
          .extracting(BaseCollectable::getId)
          .doesNotContain(unwatchedMovie.getId());
    }

    @Test
    @DisplayName("Should return empty list when called with limit zero")
    void shouldReturnEmptyListWhenCalledWithLimitZero() {
      var results = items(profileId, 0);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Should return only the most recent item when called with limit one")
    void shouldReturnOnlyTheMostRecentItemWhenCalledWithLimitOne() {
      var results = items(profileId, 1);

      assertThat(results)
          .extracting(BaseCollectable::getId)
          .containsExactly(inProgressEpisode.getId());
    }

    @Test
    @DisplayName(
        "Should order items by session progress last modified descending when querying"
            + " with pinned timestamps")
    void shouldOrderItemsBySessionProgressLastModifiedDescendingWhenQueryingWithPinnedTimestamps() {
      var results = items(profileId, 20);

      // EPISODE_ACTIVITY_AT (Feb) > MOVIE_ACTIVITY_AT (Jan) — episode must come first.
      assertThat(results)
          .extracting(BaseCollectable::getId)
          .containsExactly(inProgressEpisode.getId(), inProgressMovie.getId());
    }

    @Test
    @DisplayName("Should break a last-watched tie by id when titles were last watched at one time")
    void shouldBreakALastWatchedTieByIdWhenTitlesWereLastWatchedAtOneTime() {
      var tiedIdentity = authTestSupport.createIdentity();
      var tiedProfileId = tiedIdentity.profile().getId();
      try {
        var movieIds = new ArrayList<UUID>();
        for (var index = 0; index < TIED_TITLE_COUNT; index++) {
          var movie = createMovieWithFile("Tied Movie " + index);
          var fileId = movie.getFiles().iterator().next().getId();
          saveProgress(tiedProfileId, fileId);
          pinSessionProgressTimestamp(fileId, MOVIE_ACTIVITY_AT);
          movieIds.add(movie.getId());
        }

        // PostgreSQL orders uuid values byte by byte, which matches their text order.
        var expected =
            movieIds.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        var results = items(tiedProfileId, 20);

        assertThat(results).extracting(BaseCollectable::getId).containsExactlyElementsOf(expected);
        var positions =
            continueWatchingService.findMatchingItems(tiedProfileId, Set.copyOf(movieIds));
        assertThat(positions).containsOnlyKeys(movieIds);
        assertThat(positions.values())
            .allSatisfy(item -> assertThat(item.sortValue()).isEqualTo(MOVIE_ACTIVITY_AT));
      } finally {
        authTestSupport.deleteIdentity(tiedIdentity);
      }
    }

    private SessionProgress saveProgress(UUID owner, UUID mediaFileId) {
      return sessionProgressRepository.saveAndFlush(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .profileId(owner)
              .mediaFileId(mediaFileId)
              .positionSeconds(600)
              .percentComplete(20.0)
              .durationSeconds(3000)
              .build());
    }
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }
}
