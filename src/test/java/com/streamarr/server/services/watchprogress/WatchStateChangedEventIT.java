package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.watchprogress.events.WatchStateChangedEvent;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.WithProfileContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Watch-state writes against real PostgreSQL publish one change per committed write, and a rolled
 * back write publishes nothing.
 */
@Tag("IntegrationTest")
@DisplayName("Watch State Changed Event Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@WithProfileContext
@Import(WatchStateChangedEventIT.CommittedWatchStateChanges.class)
class WatchStateChangedEventIT extends AbstractIntegrationTest {

  private static final int PAUSED_POSITION_SECONDS = 40 * 60;
  private static final int FINISHED_POSITION_SECONDS = 7000;
  private static final Duration BOUND = Duration.ofSeconds(10);

  @Autowired private SessionProgressService sessionProgressService;
  @Autowired private WatchStatusService watchStatusService;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private CommittedWatchStateChanges committed;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;
  private Library library;
  private Library seriesLibrary;

  @BeforeAll
  void setup() {
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    seriesLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
  }

  @AfterEach
  void clearCommittedChanges() {
    committed.events.clear();
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should publish one committed change when a stop marks the movie watched")
  void shouldPublishOneCommittedChangeWhenAStopMarksTheMovieWatched() {
    var movie = createMovieWithFile();
    var session = openSession(movie);
    report(session, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    committed.events.clear();

    report(session, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED);

    assertThat(committed.events)
        .containsExactly(new WatchStateChangedEvent(profileId, Set.of(movie.getId())));
  }

  @Test
  @DisplayName("Should save one completion and publish one change when a stopped report repeats")
  void shouldSaveOneCompletionAndPublishOneChangeWhenAStoppedReportRepeats() {
    var movie = createMovieWithFile();
    var session = openSession(movie);
    report(session, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    committed.events.clear();

    report(session, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED);
    report(session, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED);

    assertThat(completionsOf(movie.getId())).hasSize(1);
    assertThat(committed.events)
        .containsExactly(new WatchStateChangedEvent(profileId, Set.of(movie.getId())));
  }

  @Test
  @DisplayName("Should save one completion when two stopped reports for one session run at once")
  void shouldSaveOneCompletionWhenTwoStoppedReportsForOneSessionRunAtOnce() throws Exception {
    var movie = createMovieWithFile();
    var session = openSession(movie);

    try (var executor =
        new DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
      var retry =
          transactionTemplate.execute(
              _ -> {
                report(session, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED);
                var pending =
                    executor.submit(
                        () -> report(session, FINISHED_POSITION_SECONDS, PlaybackState.STOPPED));
                // The retry either waits on this uncommitted completion or has already saved a
                // second completion.
                await().atMost(BOUND).until(() -> pending.isDone() || isCompletionWaitingOnLock());
                return pending;
              });

      retry.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    }

    assertThat(completionsOf(movie.getId())).hasSize(1);
    assertThat(committed.events)
        .containsExactly(new WatchStateChangedEvent(profileId, Set.of(movie.getId())));
  }

  @Test
  @DisplayName("Should publish nothing when a paused report repeats the stored position")
  void shouldPublishNothingWhenAPausedReportRepeatsTheStoredPosition() {
    var movie = createMovieWithFile();
    var session = openSession(movie);
    report(session, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);
    committed.events.clear();

    report(session, PAUSED_POSITION_SECONDS, PlaybackState.PAUSED);

    assertThat(committed.events).isEmpty();
  }

  @Test
  @DisplayName("Should publish nothing when the transaction rolls back after marking watched")
  void shouldPublishNothingWhenTheTransactionRollsBackAfterMarkingWatched() {
    var movie = createMovieWithFile();

    transactionTemplate.executeWithoutResult(
        status -> {
          watchStatusService.markWatched(
              profileId, movie.getId(), CollectableScope.MOVIE_OR_EPISODE, 0);
          status.setRollbackOnly();
        });

    assertThat(committed.events).isEmpty();
    assertThat(completionsOf(movie.getId())).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(EmptyContainer.class)
  @DisplayName(
      "Should save no completion and publish nothing when marking an empty container watched")
  void shouldSaveNoCompletionAndPublishNothingWhenMarkingAnEmptyContainerWatched(
      EmptyContainer container) {
    var containerId = create(container);

    watchStatusService.markWatched(profileId, containerId);

    assertThat(completionsOf(containerId)).isEmpty();
    assertThat(committed.events).isEmpty();
    assertThat(watchStatusOf(container, containerId)).isEqualTo(WatchStatus.UNWATCHED);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(EmptyContainer.class)
  @DisplayName(
      "Should save no completion and publish nothing when marking an empty container unwatched")
  void shouldSaveNoCompletionAndPublishNothingWhenMarkingAnEmptyContainerUnwatched(
      EmptyContainer container) {
    var containerId = create(container);

    watchStatusService.markUnwatched(profileId, containerId);

    assertThat(completionsOf(containerId)).isEmpty();
    assertThat(committed.events).isEmpty();
    assertThat(watchStatusOf(container, containerId)).isEqualTo(WatchStatus.UNWATCHED);
  }

  private enum EmptyContainer {
    SEASON_WITHOUT_EPISODES,
    SERIES_WITHOUT_SEASONS
  }

  private UUID create(EmptyContainer container) {
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Empty Series")
                .titleSort("Empty Series")
                .library(seriesLibrary)
                .build());
    return switch (container) {
      case SEASON_WITHOUT_EPISODES ->
          seasonRepository
              .saveAndFlush(
                  Season.builder().seasonNumber(1).series(series).library(seriesLibrary).build())
              .getId();
      case SERIES_WITHOUT_SEASONS -> series.getId();
    };
  }

  private WatchStatus watchStatusOf(EmptyContainer container, UUID containerId) {
    var statuses =
        switch (container) {
          case SEASON_WITHOUT_EPISODES ->
              watchStatusService.getWatchStatusForSeasons(profileId, List.of(containerId));
          case SERIES_WITHOUT_SEASONS ->
              watchStatusService.getWatchStatusForSeries(profileId, List.of(containerId));
        };
    return statuses.getOrDefault(containerId, WatchStatus.UNWATCHED);
  }

  private List<WatchHistory> completionsOf(UUID itemId) {
    return watchHistoryRepository.findByProfileIdAndCollectableIdIn(profileId, List.of(itemId));
  }

  private boolean isCompletionWaitingOnLock() {
    dsl.execute("SELECT pg_stat_clear_snapshot()");
    return dsl.fetchSingle(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE wait_event_type = 'Lock'
                AND query ILIKE '%insert%watch_history%'
            )
            """)
        .get(0, Boolean.class);
  }

  private Movie createMovieWithFile() {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("movie-" + UUID.randomUUID() + ".mkv")
            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
            .build();

    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Movie " + UUID.randomUUID())
            .files(Set.of(file))
            .library(library)
            .build());
  }

  private StreamSession openSession(Movie movie) {
    var session =
        defaultSessionBuilder()
            .mediaFileId(movie.getFiles().iterator().next().getId())
            .authority(playbackAuthorityFor(profileId))
            .build();
    runtimeRegistry.save(session);
    return session;
  }

  private void report(StreamSession session, int positionSeconds, PlaybackState state) {
    sessionProgressService.reportStreamSessionTimeline(
        profileId, session.getSessionId(), positionSeconds, state);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CommittedWatchStateChanges {

    final List<WatchStateChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCommitted(WatchStateChangedEvent event) {
      events.add(event);
    }
  }
}
